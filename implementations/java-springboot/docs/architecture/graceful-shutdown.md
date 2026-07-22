# Graceful Shutdown (Spring Boot)

> For the framework-agnostic principles, see the root [graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md).

## Current implementation

`build.gradle` has the `spring-boot-starter-actuator` dependency, and `application.yml` has both `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` and the `management.health.livenessState`/`readinessState` (probes) settings fully in place — everything below is exactly the actual code.

---

## `server.shutdown: graceful` — a built-in Spring Boot feature

Spring Boot 2.3+ supports graceful shutdown with a single line of configuration, no separate library needed.

```yaml
# application.yml — would need to be added
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # corresponds to the root's terminationGracePeriodSeconds
```

The sequence Spring automatically performs on receiving SIGTERM:

```
1. The embedded server (Tomcat) stops accepting new requests
2. Waits for in-flight requests to finish processing (up to timeout-per-shutdown-phase)
3. ApplicationContext shuts down — @PreDestroy and DisposableBean callbacks run
4. Process exits
```

This built-in mechanism guarantees, at the framework level, the ordering the root emphasizes — that "the readiness transition happens before the HTTP server shuts down" — with no need to implement a separate `isShuttingDown` flag by hand.

---

## Liveness / Readiness probes — Actuator

```groovy
// build.gradle — would need to be added
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

```yaml
# application.yml — to add
management:
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessState:
      enabled: true
    readinessState:
      enabled: true
```

```
GET /actuator/health/liveness   → 200: process alive (still 200 even while shutting down — prevents an unwanted restart)
GET /actuator/health/readiness  → 200: ready to accept traffic / 503: shutting down
```

Spring's `AvailabilityChangeEvent` automatically links SIGTERM receipt with `readinessState` — once `graceful` shutdown begins, Spring switches to `ReadinessState.REFUSING_TRAFFIC`, so `/actuator/health/readiness` immediately returns 503, while `LivenessState` stays at `CORRECT` (200) until the process actually dies. The root's principle that "Liveness always stays 200 even while shutting down" is already satisfied by Actuator's default behavior — there's no need for application code to toggle the two states directly.

```yaml
# Kubernetes example
spec:
  terminationGracePeriodSeconds: 40   # give it more margin than spring.lifecycle.timeout-per-shutdown-phase (30s)
  containers:
    - livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
```

---

## Running the process directly in the container — already covered in [container.md](container.md)

`ENTRYPOINT ["java", "-jar", "app.jar"]` (exec form) is required for the JVM to become PID 1 and receive SIGTERM immediately — wrapping it in something like `./gradlew bootRun` inserts the Gradle process in between, delaying or losing signal delivery. See [container.md](container.md) for details.

---

## Resource cleanup order — the HikariCP connection pool

Spring Boot's graceful-shutdown order (HTTP server shutdown → `ApplicationContext` shutdown) naturally guarantees that DataSource (HikariCP) shutdown happens last — since the `DataSource` bean is just another Spring-managed bean, in-flight requests can keep using DB connections until `ApplicationContext` fully shuts down. No manual control over the cleanup order is needed.

If custom cleanup logic is added via `@PreDestroy`, take care not to let it throw — an exception during cleanup can cause other `@PreDestroy` callbacks to be skipped.

```java
// If there's a custom resource (proposal)
@PreDestroy
public void cleanup() {
    try {
        customResource.close();
    } catch (Exception e) {
        log.error("Resource cleanup failed", e);   // log only, never throw
    }
}
```

---

## A real custom resource — `OutboxConsumer`'s `SmartLifecycle`

This repository has a real custom resource where the proposal above is genuinely needed: a dedicated background thread that waits on SQS via long polling (`OutboxConsumer`, see [domain-events.md](domain-events.md)). `SmartLifecycle` is used instead of `@PreDestroy` — `@PreDestroy` only provides a shutdown hook, but `SmartLifecycle` **also provides a startup hook (`start()`)**, letting the background loop be started automatically the moment `ApplicationContext` refresh completes (no separate `@PostConstruct`/`ApplicationListener` combination is needed).

```java
// outbox/OutboxConsumer.java — actual code (excerpt)
@Component
public class OutboxConsumer implements SmartLifecycle {
    private volatile boolean running = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-consumer"));

    @Override
    public void start() {
        running = true;
        executor.submit(this::pollLoop);   // returns immediately — doesn't block bootstrap
    }

    @Override
    public void stop() {                   // called automatically during graceful shutdown
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
```

That `stop()` never throws (catching `InterruptedException` and only restoring the thread's interrupt flag) and waits, via `awaitTermination(...)`, for any in-flight `ReceiveMessage` call (up to `waitTimeSeconds`) to finish is for the same reason as the "never throw from `@PreDestroy`" principle above — an exception thrown from `SmartLifecycle.stop()` during cleanup can likewise affect other beans' shutdown callbacks.

---

## Principles

- **`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` must be configured**: this is done.
- **Enable Actuator's liveness/readiness probes**: `management.health.livenessState/readinessState.enabled: true`.
- **Set the orchestrator's `terminationGracePeriodSeconds` with more margin than `timeout-per-shutdown-phase`.**
- **Use exec-form ENTRYPOINT**: `["java", "..."]` — see [container.md](container.md).
- **Never throw from `@PreDestroy`**: wrap in try-catch and only log.

---

### Related documents

- [container.md](container.md) — Dockerfile ENTRYPOINT, Actuator health checks
- [observability.md](observability.md) — shutdown-related logging
