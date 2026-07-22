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

## Metrics and tracing — Spring Boot Actuator + Micrometer

`build.gradle` has both `spring-boot-starter-actuator` (for exposing health probes — see [graceful-shutdown.md](graceful-shutdown.md)) and a Micrometer registry for Prometheus scraping:

```groovy
// build.gradle — actual code
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
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
```

`GET /actuator/prometheus` exposes the Prometheus scrape endpoint. HTTP request count/latency (`http.server.requests`), JVM memory, and DB connection pool (HikariCP) metrics are all collected automatically with no separate instrumentation code — Spring Boot Actuator supports the root's "metrics stack is unspecified, but Prometheus is recommended" direction at the framework level by default.

Tracing can be integrated with OpenTelemetry via `spring-boot-starter-actuator` + Micrometer Tracing (`micrometer-tracing-bridge-otel`) — this repository has not adopted it yet.

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
