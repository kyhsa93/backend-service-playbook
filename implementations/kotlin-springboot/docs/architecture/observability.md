# Observability — Kotlin Spring Boot

> For the framework-agnostic principles, see [root observability.md](../../../../docs/architecture/observability.md).

## Current logging — direct SLF4J usage + structured logs (fluent API) side by side

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — actual code
private val logger = LoggerFactory.getLogger(NotificationServiceImpl::class.java)

logger.atInfo()
    .addKeyValue("account_id", accountId)
    .addKeyValue("event_type", eventType)
    .addKeyValue("recipient", recipient)
    .addKeyValue("ses_message_id", result.messageId())
    .log("Email sent")
```

```kotlin
// outbox/OutboxPoller.kt — actual code
logger
    .atError()
    .addKeyValue("event_type", row.eventType)
    .addKeyValue("event_id", row.eventId)
    .setCause(it)
    .log("Failed to publish to SQS")
```

```kotlin
// account/interfaces/rest/AccountController.kt — actual code
logger.warn("Account not found: {}", e.message)
```

`notification/infrastructure/NotificationServiceImpl`/`outbox/OutboxPoller` already leave **snake_case field-based structured logs** using the SLF4J 2.x fluent API (`atInfo()`/`atError().addKeyValue(...)`) — `account_id`/`event_type`/`event_id`, etc. are split out into separate key-value pairs instead of being buried in the message string, enabling per-field search/aggregation in a monitoring system. `AccountController`/`OutboxConsumer` (`logger.error("Failed to receive from SQS", e)`) still use only parameterized text logs (a `{}` placeholder, or just message+exception) — they avoid unnecessary string creation from string interpolation (`"...${accountId}..."`), but haven't gone as far as key-value structuring. Correlation ID is propagated via MDC by `CorrelationIdFilter`, covered below.

---

## Direct SLF4J usage vs. `kotlin-logging` — the choice and its rationale

This repository doesn't yet use a Kotlin-specific logging library like `kotlin-logging` (no dependency in `build.gradle.kts`). The two approaches are compared below.

| | `LoggerFactory.getLogger(X::class.java)` (current) | `KotlinLogging.logger {}` |
|---|---|---|
| Declaration | `private val logger = LoggerFactory.getLogger(Foo::class.java)` | `private val logger = KotlinLogging.logger {}` (class name auto-inferred) |
| Message evaluation | eager — string concatenation cost can occur even when the log level is off | lazy lambda evaluation — `logger.debug { "expensive computation: ${expensive()}" }` doesn't even run when debug is disabled |
| Dependency | none (SLF4J is included in Spring Boot by default) | needs adding `io.github.microutils:kotlin-logging-jvm` |
| Kotlin-ness | `::class.java` boilerplate | no need to specify the class name via a top-level extension property |

**Recommendation**: switch new code to `kotlin-logging`. Lazy lambda evaluation gives a real performance benefit at the `debug`/`trace` level, and removing the repeated `::class.java` gets Kotlin-native conciseness. There's no need to immediately change existing `LoggerFactory.getLogger(...)` code — both approaches operate on top of the SLF4J API internally, so they can coexist.

```kotlin
// build.gradle.kts — when adding it
implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
```

```kotlin
// switch-over example
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.info { "Email sent: accountId=$accountId, eventType=$eventType" }   // a lambda — the string is only built if info is enabled
```

---

## Structured logging — JSON + snake_case (logstash-logback-encoder)

Both `build.gradle.kts` and `logback-spring.xml` are already equipped with a JSON encoder — under the prod profile it emits JSON logs that can be ingested as-is by CloudWatch/Datadog etc., while locally/in tests it emits human-readable text logs.

```kotlin
// build.gradle.kts — actual code
implementation("net.logstash.logback:logstash-logback-encoder:7.4")
```

```xml
<!-- logback-spring.xml — actual code -->
<configuration>
  <springProfile name="prod">
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
      <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdcKeyName>correlationId</includeMdcKeyName>
      </encoder>
    </appender>
    <root level="INFO"><appender-ref ref="JSON" /></root>
  </springProfile>
  <springProfile name="!prod">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
      <encoder><pattern>%d{HH:mm:ss} [%X{correlationId}] %-5level %logger{36} - %msg%n</pattern></encoder>
    </appender>
    <root level="DEBUG"><appender-ref ref="CONSOLE" /></root>
  </springProfile>
</configuration>
```

When leaving a structured log, use a `Marker` or key-value pairs — from SLF4J 2.x on, fields can be attached explicitly via the fluent API, and `NotificationServiceImpl` actually uses this pattern (see "Current logging" above). **Field names use snake_case** (`account_id`, `event_type`) per the root principle — Kotlin property names are camelCase (`accountId`), but log fields follow monitoring-platform convention. Logs that still use only a `{}` placeholder, like `EventHandlerRegistry` (the unknown-eventType warning)/`AccountController`, can be promoted to the same fluent API as needed (there's room for improvement left, but it's not a required gap) — the failure logging in `OutboxPoller`/`OutboxConsumer` (SQS publish/receive · handler processing failures) already uses the `addKeyValue(...)` structured-log style.

---

## Correlation ID — MDC propagation

`CorrelationIdFilter` (actual code, `common/CorrelationIdFilter.kt`), covered in [cross-cutting-concerns.md](cross-cutting-concerns.md), puts the value into MDC when a request comes in. From then on, it's accessible anywhere in the application via `MDC.get("correlationId")`, and `logstash-logback-encoder`'s `includeMdcKeyName` automatically includes it as a log field — there's no need to pass correlationId as an argument on each log call.

```kotlin
// service logic — automatically included in the log even without passing correlationId as a parameter
logger.info { "Account creation complete: accountId=$accountId" }
// output (JSON): {"message":"Account creation complete...", "correlationId":"a1b2c3...", ...}
```

The role Node's `AsyncLocalStorage` plays is handled in the JVM thread model by **MDC (`ThreadLocal`-based)** — since Spring MVC assigns a fixed thread per request, this fits naturally. However, at a point that hands off to `@Async` or a separate thread pool (coroutines, `CompletableFuture`, etc), MDC isn't propagated automatically, so it must be copied explicitly — this repository currently uses neither coroutines nor async processing (→ [scheduling.md](scheduling.md)), so this issue doesn't arise.

---

## Logging criteria per layer — how they're applied in this repository

| Layer | Current code | Assessment |
|---|---|---|
| `account/domain/` | no logging | matches the root principle — the Aggregate stays framework-free |
| `outbox/OutboxPoller`, `outbox/OutboxConsumer` | `logger.atError().addKeyValue(...).log(...)` (on SQS publish/receive · handler processing failure) | matches — error logging in shared infrastructure (`outbox/`) |
| `notification/infrastructure/NotificationServiceImpl` | `logger.info(...)` (send success) | matches — logging an external integration's result in Infrastructure |
| `account/interfaces/rest/AccountController` | `logger.warn(...)` (failure logging in `@ExceptionHandler`) | matches — logging of the HTTP request/response itself is handled by [cross-cutting-concerns.md](cross-cutting-concerns.md)'s `RequestLoggingInterceptor` (actual code, `common/RequestLoggingInterceptor.kt`) |

---

## Principle summary

- **Logging is forbidden in the Domain layer**: `Account`, `Money`, `Transaction` never import a logger. The harness's `no-logging-in-domain` rule mechanically checks for SLF4J/`kotlin-logging` usage in `domain/`.
- **Empty catch blocks are forbidden**: if an exception is caught but neither logged nor rethrown, the failure gets silently buried. The harness's `no-silent-catch` rule narrowly checks `application/`, `infrastructure/` for completely empty `catch (e: Exception) { }` blocks (only blocks that are truly empty, to avoid false positives).
- **`NotificationServiceImpl` uses structured logs**: it leaves snake_case fields via `logger.atInfo().addKeyValue(...)`. The failure logging in `OutboxPoller`/`OutboxConsumer` already does the same — promoting `EventHandlerRegistry`/`AccountController`'s `{}`-placeholder logs to the same style is remaining room for improvement.
- **Consider adopting `kotlin-logging`**: lazy evaluation + removing the `::class.java` boilerplate — not yet adopted.
- **Correlation ID is propagated via MDC**: once `CorrelationIdFilter` sets it at the request entry point, it's automatically included in every subsequent log.

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the Correlation ID injection filter
- [layer-architecture.md](layer-architecture.md) — per-layer responsibility separation
