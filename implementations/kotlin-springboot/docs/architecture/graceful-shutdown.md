# Graceful Shutdown — Kotlin Spring Boot

> For the framework-agnostic principles, see [root graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md).

## Current implementation

`examples/src/main/resources/application.yml` has both `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` and the `management.health.livenessstate`/`readinessstate` (probes) settings reflected, and `build.gradle.kts` also has the `spring-boot-starter-actuator` dependency added — everything below is the actual code, as documented. `SecurityConfig`'s whitelist has also been updated with the `/actuator/health/**` path that's actually being served.

---

## `server.shutdown: graceful` — what one line gets you, actual code

```yaml
# application.yml — actual code
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # shorter than terminationGracePeriodSeconds
```

Spring Boot 2.3+ automatically handles the following with just `server.shutdown: graceful`:

1. SIGTERM received → the embedded Tomcat **immediately stops accepting new requests**
2. In-flight requests are given until `timeout-per-shutdown-phase` to finish
3. If all finish within the timeout, shutdown completes normally; otherwise it's forced

There's no need to manually implement a readiness toggle like in Node/NestJS — "reject new connections" already happens at the Tomcat connector level. Instead, **a separate orchestrator (Kubernetes) readiness probe is needed** — Tomcat rejecting new requests, and the load balancer not sending traffic to this instance in the first place, are two separate mechanisms.

---

## Liveness / Readiness — Spring Boot Actuator, actual code

The Actuator health-probe configuration actually applied in `application.yml` is as follows.

```yaml
# application.yml — actual code
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
```

```
GET /actuator/health/liveness   → always 200 (as long as the process is alive)
GET /actuator/health/readiness  → automatically flips to 503 when SIGTERM is received
```

Spring Boot's `ApplicationAvailability` mechanism automatically flips `ReadinessState` to `REFUSING_TRAFFIC` when it receives SIGTERM — the ordering the root requires ("readiness flips before the HTTP server shuts down") is guaranteed by Spring Boot's shutdown lifecycle (the `ContextClosedEvent` handling order), without writing any extra code.

```yaml
# Kubernetes manifest
spec:
  terminationGracePeriodSeconds: 30   # give more headroom than timeout-per-shutdown-phase
  containers:
    - livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
    - readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
```

---

## Running the process directly in the container

The principle already covered in [container.md](container.md) applies here as-is too.

```dockerfile
CMD ["java", "-jar", "app.jar"]   # exec form — java is PID 1, receives SIGTERM immediately
```

By default, the JVM reacts to SIGTERM by running its registered shutdown hook (Spring Boot's `ApplicationContext.close()` is automatically registered as one) — there's no need to write `Runtime.getRuntime().addShutdownHook { }` yourself. `SpringApplication.run()` already handles it.

---

## Resource cleanup order — HikariCP, SES/S3 clients

Spring Boot calls a Bean's `@PreDestroy`/`DisposableBean` at **application-context shutdown time** (after the HTTP server has stopped accepting new requests). The HikariCP connection pool, `SesClient`/`S3Presigner`, etc. are all automatically `close()`d — if it's a Spring-managed Bean, manual cleanup code is rarely needed.

If there's a resource that does need manual cleanup:

```kotlin
@Component
class SomeResource : DisposableBean {
    override fun destroy() {
        // cleanup logic — never throw. Just log on failure so it doesn't block other Beans' cleanup
        runCatching { /* cleanup */ }.onFailure { logger.error(it) { "Resource cleanup failed" } }
    }
}
```

---

## In-flight `@Scheduled`/Outbox polling work

A component that polls via `@Scheduled`, like the `OutboxPoller` covered in [scheduling.md](scheduling.md), can have a batch cut off mid-poll at shutdown time. If you're using a `ThreadPoolTaskScheduler`, configure the shutdown wait as shown below. `OutboxConsumer` is implemented not as `@Scheduled` but as `SmartLifecycle` + an infinite loop on a dedicated thread (`waitTimeSeconds` long polling), so its shutdown wait is handled not through `ThreadPoolTaskScheduler` configuration but via `Thread.join()` inside `SmartLifecycle.stop()` (see the actual code in `outbox/OutboxConsumer.kt` — `stop()` calls `workerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS)`).

```kotlin
@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(20)
        }
}
```

`setWaitForTasksToCompleteOnShutdown(true)` is applying the root's "resources should finish safely during cleanup" to the scheduler's thread pool.

---

## Principle summary

- **`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`** — a one-line setting gets you both new-request rejection and waiting for in-flight requests.
- **Actuator liveness/readiness probes are required**: `ApplicationAvailability` automatically flips readiness in reaction to SIGTERM.
- **exec-form CMD**: the JVM is PID 1 and receives SIGTERM directly.
- **Spring Beans are cleaned up automatically**: HikariCP, AWS SDK clients, etc. are auto-`close()`d at context shutdown.
- **A Scheduler explicitly sets its shutdown wait**: `setWaitForTasksToCompleteOnShutdown(true)`.

### Related documents

- [container.md](container.md) — the Dockerfile CMD, Actuator health-check configuration
- [scheduling.md](scheduling.md) — how `@Scheduled` components respond to shutdown
- [observability.md](observability.md) — logging the shutdown process
