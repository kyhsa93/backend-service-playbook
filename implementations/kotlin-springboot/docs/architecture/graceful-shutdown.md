# Graceful Shutdown — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md) 참조.

## 현재 구현

`examples/src/main/resources/application.yml`에 `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`와 `management.health.livenessstate`/`readinessstate`(probes) 설정이 모두 반영되어 있고, `build.gradle.kts`에 `spring-boot-starter-actuator` 의존성도 추가되어 있다 — 아래 문서 내용 그대로 실제 코드다. `SecurityConfig`의 화이트리스트도 실제로 서빙되는 `/actuator/health/**` 경로로 갱신되어 있다.

---

## `server.shutdown: graceful` — 한 줄로 얻는 것, 실제 코드

```yaml
# application.yml — 실제 코드
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # terminationGracePeriodSeconds보다 짧게
```

Spring Boot 2.3+는 `server.shutdown: graceful` 하나로 다음을 자동 처리한다:

1. SIGTERM 수신 → 내장 톰캣(Tomcat)이 **새 요청 수신을 즉시 중단**
2. 진행 중인 요청은 `timeout-per-shutdown-phase`까지 완료 대기
3. 타임아웃 내에 모두 끝나면 정상 종료, 넘으면 강제 종료

Node/NestJS처럼 readiness 토글을 수동으로 구현할 필요가 없다 — 톰캣 커넥터 레벨에서 "새 연결 거부"가 이미 이루어진다. 대신 **오케스트레이터(Kubernetes)의 readiness 프로브가 별도로 필요**하다 — 톰캣이 새 요청을 거부하는 것과, 로드밸런서가 애초에 트래픽을 이 인스턴스로 보내지 않는 것은 별개의 메커니즘이다.

---

## Liveness / Readiness — Spring Boot Actuator, 실제 코드

`application.yml`에 실제로 적용된 Actuator 헬스 프로브 설정은 다음과 같다.

```yaml
# application.yml — 실제 코드
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
GET /actuator/health/liveness   → 항상 200 (프로세스가 살아있는 한)
GET /actuator/health/readiness  → SIGTERM 수신 시 자동으로 503 전환
```

Spring Boot의 `ApplicationAvailability` 메커니즘이 SIGTERM을 받으면 `ReadinessState`를 `REFUSING_TRAFFIC`으로 자동 전환한다 — root가 "readiness 전환이 HTTP 서버 종료보다 먼저"라고 요구하는 순서를, 별도 코드 작성 없이 Spring Boot의 종료 라이프사이클(`ContextClosedEvent` 처리 순서)이 보장한다.

```yaml
# Kubernetes 매니페스트
spec:
  terminationGracePeriodSeconds: 30   # timeout-per-shutdown-phase보다 여유 있게
  containers:
    - livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
    - readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
```

---

## 컨테이너에서 직접 프로세스 실행

[container.md](container.md)에서 이미 다룬 원칙이 여기서도 그대로 적용된다.

```dockerfile
CMD ["java", "-jar", "app.jar"]   # exec form — java가 PID 1로 SIGTERM 즉시 수신
```

JVM은 기본적으로 SIGTERM에 반응해 등록된 shutdown hook(Spring Boot의 `ApplicationContext.close()`가 자동 등록)을 실행한다 — `Runtime.getRuntime().addShutdownHook { }`을 직접 작성할 필요는 없다. `SpringApplication.run()`이 이미 처리한다.

---

## 리소스 정리 순서 — HikariCP, SES/S3 클라이언트

Spring Boot는 Bean의 `@PreDestroy`/`DisposableBean`을 **애플리케이션 컨텍스트 종료 시점**(HTTP 서버가 새 요청을 받지 않게 된 이후)에 호출한다. HikariCP 커넥션 풀, `SesClient`/`S3Presigner` 등은 자동으로 `close()`된다 — Spring이 관리하는 Bean이면 수동 정리 코드가 거의 필요 없다.

수동 정리가 필요한 리소스가 있다면:

```kotlin
@Component
class SomeResource : DisposableBean {
    override fun destroy() {
        // 정리 로직 — 예외를 던지지 않는다. 다른 Bean 정리를 막지 않도록 로그만 남긴다
        runCatching { /* cleanup */ }.onFailure { logger.error(it) { "리소스 정리 실패" } }
    }
}
```

---

## 진행 중인 `@Scheduled`/Outbox 폴링 작업

[scheduling.md](scheduling.md)에서 다루는 `OutboxPoller`처럼 `@Scheduled`로 폴링하는 컴포넌트는, 종료 시점에 폴링 도중이던 배치가 끊길 수 있다. `ThreadPoolTaskScheduler`를 사용한다면 아래처럼 종료 대기를 설정한다. `OutboxConsumer`는 `@Scheduled`가 아니라 `SmartLifecycle` + 전용 스레드의 무한 루프(`waitTimeSeconds` long polling)로 구현되어 있으므로, 종료 시 대기는 `ThreadPoolTaskScheduler` 설정이 아니라 `SmartLifecycle.stop()`에서의 `Thread.join()`으로 처리한다(`outbox/OutboxConsumer.kt` 실제 코드 — `stop()`이 `workerThread.join(SHUTDOWN_JOIN_TIMEOUT_MS)`를 호출).

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

`setWaitForTasksToCompleteOnShutdown(true)`가 root의 "정리 중 리소스는 안전하게 마무리"를 스케줄러 스레드 풀에 적용한 것이다.

---

## 원칙 요약

- **`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`** — 한 줄 설정으로 새 요청 거부 + 진행 중 요청 대기를 얻는다.
- **Actuator liveness/readiness 프로브 필수**: `ApplicationAvailability`가 SIGTERM에 자동 반응해 readiness를 전환한다.
- **exec form CMD**: JVM이 PID 1로 SIGTERM을 직접 수신한다.
- **Spring Bean은 자동으로 정리된다**: HikariCP, AWS SDK 클라이언트 등은 컨텍스트 종료 시 자동 `close()`.
- **Scheduler는 종료 대기를 명시적으로 설정**: `setWaitForTasksToCompleteOnShutdown(true)`.

### 관련 문서

- [container.md](container.md) — Dockerfile CMD, Actuator 헬스체크 설정
- [scheduling.md](scheduling.md) — `@Scheduled` 컴포넌트의 종료 대응
- [observability.md](observability.md) — 종료 과정 로깅
