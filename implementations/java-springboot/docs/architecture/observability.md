# Observability — Logging, Metrics, Tracing (Spring Boot)

> For the framework-agnostic principles, see the root [observability.md](../../../../docs/architecture/observability.md).

## Structured logging — the Logback JSON encoder + `StructuredArguments`

Every logging call in this repository explicitly structures its fields via `net.logstash.logback.argument.StructuredArguments.kv(...)`:

```java
// account/infrastructure/notification/NotificationServiceImpl.java — actual code
private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
// ...
log.info("Email sent", kv("account_id", accountId), kv("event_type", eventType),
        kv("recipient", recipient), kv("ses_message_id", response.messageId()));
```

```java
// outbox/OutboxPoller.java — actual code
log.error("SQS publish failed", kv("event_type", event.getEventType()), kv("event_id", event.getEventId()), e);
```

```java
// account/interfaces/rest/AccountController.java — actual code
log.warn("Account request failed", kv("code", e.code()), kv("message", e.getMessage()));
```

In all three places, the log message string itself ("Email sent", "Event handling failed", "Account request failed") is kept short, while every other field is added as a separate field in the JSON output via `kv(...)` — a log aggregator (CloudWatch, Datadog, etc.) can then index `account_id`/`event_type` as individual fields.

---

## The Logback JSON encoder (`logback-spring.xml`)

```groovy
// build.gradle — actual code
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

```xml
<!-- src/main/resources/logback-spring.xml — actual code -->
<configuration>
    <springProfile name="!prod">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder><pattern>%d{HH:mm:ss} %-5level %logger{36} - %msg%n</pattern></encoder>
        </appender>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>

    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlation_id</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="JSON"/></root>
    </springProfile>
</configuration>
```

**Human-readable plain text locally, JSON in production** — branched via `springProfile`. The JSON encoder automatically includes MDC's `correlation_id` as a log field (see `CorrelationIdFilter` in [cross-cutting-concerns.md](cross-cutting-concerns.md)). The gating profiles are `prod`/`!prod` — the same profile names and polarity as `SecretsEnvironmentPostProcessor` in [secret-manager.md](secret-manager.md). Even a local run with no active profile matches `!prod`, so plain-text logging is the default — using a profile name that's never activated anywhere, like `local`/`!local`, is avoided, since it would create a latent bug where only the JSON encoder ever applies.

### Field naming — passing structured arguments in snake_case

```java
// the pattern used in the actual code
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("Email sent", kv("account_id", accountId), kv("event_type", eventType), kv("ses_message_id", response.messageId()));
```

`StructuredArguments.kv(...)` never appears in the log message string — it's only added as a separate field in the JSON output. Unlike SLF4J's `{}` placeholders, field names can be controlled explicitly, making it easy to enforce the root's "snake_case field names" rule. `NotificationServiceImpl`/`OutboxPoller`/`OutboxConsumer`/`AccountController` all use this approach — follow this same pattern when adding new logging calls.

---

## Logging criteria per layer

| Layer | What to log | Current state in this repository |
|--------|----------|------|
| Interfaces (Controller) | Request errors | Present — `AccountController`'s `@ExceptionHandler` records via `log.warn(...)` before converting to a response |
| Application (Service) | Business events, external call results | Partial — only `outbox.OutboxPoller`/`outbox.OutboxConsumer` log; the Command Services don't log |
| Infrastructure | External integration failure/retries | `NotificationServiceImpl` (SES send success/failure) |
| Domain | **Logging is forbidden** | Enforced — `Account` never imports a logger |

**Logging happens at the point where `AccountException` is thrown.** `@ExceptionHandler` records at `warn` level before converting the exception into an `ErrorResponse` — following the root principle ("an error must always be logged before it's thrown or returned as a response"):

```java
// AccountController.java — actual code
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    HttpStatus status = e.code() == AccountException.ErrorCode.ACCOUNT_NOT_FOUND
            ? HttpStatus.NOT_FOUND
            : HttpStatus.BAD_REQUEST;
    log.warn("Account request failed", kv("code", e.code()), kv("message", e.getMessage()));
    return ResponseEntity.status(status).body(ErrorResponse.of(status, e.code().name(), e.getMessage()));
}
```

---

## Correlation ID — MDC-based propagation

The Correlation ID's injection point (`Filter`) and its MDC storage method are covered in [cross-cutting-concerns.md](cross-cutting-concerns.md). Every logging call afterward automatically picks up the value from MDC (when `includeMdcKeyName` is specified in the JSON encoder config), so application code never needs to explicitly pass a `correlationId` parameter each time — Java's `MDC.get(...)` replaces the root's `AsyncLocalStorage.getStore()` pattern.

```java
// When an explicit lookup is needed (rare — the encoder usually includes it automatically)
String correlationId = MDC.get("correlation_id");
```

---

## Metrics and tracing — Spring Boot Actuator + Micrometer + OpenTelemetry

`build.gradle` has `spring-boot-starter-actuator` (for exposing health probes — see [graceful-shutdown.md](graceful-shutdown.md)), a Micrometer registry for Prometheus scraping, and Micrometer Tracing's OpenTelemetry bridge:

```groovy
// build.gradle — actual code
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
// Spring Boot 4 extracted OTel-tracing autoconfiguration out of
// spring-boot-actuator-autoconfigure into its own module (no "starter" artifact exists for it,
// unlike metrics' spring-boot-starter-micrometer-metrics) — same module-split pattern as the
// Flyway/Jackson2 workarounds elsewhere in this file. Without it, io.micrometer.tracing.Tracer
// never gets a bean definition even with the bridge/OTLP exporter jars below on the classpath.
implementation 'org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry'
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

```yaml
# application.yml — actual code
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  metrics:
    tags:
      application: account-service
  tracing:
    sampling:
      probability: 1.0
    propagation:
      type: W3C
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

`GET /actuator/prometheus` exposes the Prometheus scrape endpoint (`SecurityConfig` permits it the same way as `/actuator/health/**`, since a scraper never carries an app-issued JWT). HTTP request count/latency (`http.server.requests`), JVM memory, and DB connection pool (HikariCP) metrics are all collected automatically with no separate instrumentation code — Spring Boot Actuator supports the root's "metrics stack is unspecified, but Prometheus is recommended" direction at the framework level by default.

For tracing, `management.otlp.tracing.endpoint` follows this repository's usual "env var override, sane local-dev default" pattern (the same shape as `jwt.secret`/the `aws.*` properties) — the default points at an OTLP collector on `localhost:4318`, but nothing needs to actually be listening there for the app (or its tests) to run: the OTel SDK exports spans on a background batch-processor thread, so an unreachable endpoint only logs an export warning, it never fails a request. `management.tracing.propagation.type: W3C` pins the propagation format to the W3C `traceparent` header this doc's Outbox section relies on.

Once the bridge is on the classpath, Spring Boot auto-registers MDC population of `traceId`/`spanId` for the lifetime of the current span — **no application code sets these**. `logback-spring.xml` only needed one addition to pick them up:

```xml
<!-- logback-spring.xml — actual code -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
    <includeMdcKeyName>correlation_id</includeMdcKeyName>
    <includeMdcKeyName>traceId</includeMdcKeyName>
    <includeMdcKeyName>spanId</includeMdcKeyName>
</encoder>
```

(`includeMdcKeyName` switches `LogstashEncoder` into allow-list mode — before this change, `traceId`/`spanId` were already in MDC but silently dropped from the JSON output because only `correlation_id` was listed. Note `traceId`/`spanId` are hardcoded MDC key names set by the Micrometer Tracing bridge itself — they stay camelCase even though this repo's own field-naming rule elsewhere calls for snake_case.)

### Carrying the trace across the Outbox's async boundary

The root doc's tracing note — "at an asynchronous boundary, including `traceparent` in the outbox payload propagates the trace context" — is implemented via the exact same mechanism `eventType` already uses to cross that boundary: an SQS **message attribute**, not the JSON payload body.

```java
// outbox/OutboxWriter.java — actual code: reads the traceparent off the currently active span (the
// HTTP request span, or the span OutboxConsumer started if this write happens from inside an
// OutboxEventHandler) and stores it on the OutboxEvent row
private String currentTraceparent() {
    Span span = tracer.currentSpan();
    if (span == null) {
        return null;
    }
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(span.context(), carrier, (c, k, v) -> { if (c != null) c.put(k, v); });
    return carrier.get("traceparent");
}
```

```java
// outbox/OutboxPoller.java — actual code: forwarded as a message attribute alongside eventType
if (event.getTraceparent() != null) {
    attributes.put("traceparent", stringAttribute(event.getTraceparent()));
}
```

```java
// outbox/OutboxConsumer.java — actual code: extracts it back into a real (continued) Span before
// dispatching to the OutboxEventHandler, so the handler's logs — and any Outbox row it writes in
// turn — carry the same traceId as the original HTTP request
private Span startSpan(String traceparent) {
    Span.Builder builder = traceparent != null
            ? propagator.extract(Map.of("traceparent", traceparent), (carrier, key) -> carrier.get(key))
            : tracer.spanBuilder();
    return builder.name("outbox.consume").start();
}
```

`handleMessage()` wraps the dispatch in `tracer.withSpan(span)` and ends the span in a `finally` block. `OutboxEvent.traceparent` is a nullable column (`db/migration/V11__add_traceparent_to_outbox.sql`) — a row written with no active span (tracing disabled, or a write with no request/consumer context) simply carries none, and the Consumer falls back to starting a fresh root span, same as the very first event in a trace.

Note this repository has no prior `correlation_id`-through-Outbox precedent to copy — `CorrelationIdFilter`'s MDC value never previously survived the Outbox hop. The message-attribute mechanism above is the one genuinely reusable piece of existing plumbing (already used for `eventType`), so `traceparent` was threaded through the same way rather than inventing a new channel.

---

## Principles

- **Logging is forbidden in the Domain layer**: `Account` follows this principle — maintain it when adding new domain methods too.
- **Switch to structured logs**: use the Logback JSON encoder + `StructuredArguments.kv(...)` to explicitly build snake_case fields.
- **Errors must always be logged**: `AccountController`'s `@ExceptionHandler` records via `log.warn(...)`.
- **Propagate the Correlation ID via MDC**: injected by a `Filter` (`CorrelationIdFilter`), automatically included by the JSON encoder.
- **Expose metrics via Actuator + Micrometer**: both Actuator and the Micrometer-Prometheus registry are present, so `GET /actuator/prometheus` exposes HTTP/JVM/DB metrics with no separate instrumentation code — see "Metrics and tracing" above.

---

## Harness verification

`harness/src/rules/NoLoggingInDomain.java` (rule: `no-logging-in-domain`) fails the build if it finds `org.slf4j`/`@Slf4j`/`LoggerFactory` usage in `domain/` — an automated check of the "logging is forbidden in the Domain layer" principle above. `harness/src/rules/NoSilentCatch.java` (rule: `no-silent-catch`) fails the build if it finds a completely empty `catch (...) {}` block (with neither logging nor a rethrow) in `application/`·`infrastructure/` — this catches the most common path by which the "errors must always be logged" principle gets silently broken. Both use a narrow blocklist approach, so they don't false-positive on legitimate exception-handling code.

---

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where the Correlation ID is injected (`Filter`)
- [layer-architecture.md](layer-architecture.md) — separation of responsibility per layer
- [graceful-shutdown.md](graceful-shutdown.md) — Actuator health probes
