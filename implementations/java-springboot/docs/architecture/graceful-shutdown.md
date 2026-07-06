# Graceful Shutdown (Spring Boot)

> 프레임워크 무관 원칙은 루트 [graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md) 참고.

## 현재 상태 — 알려진 gap

`build.gradle`에 Actuator 의존성이 없고 `application.yml`에 `server.shutdown` 설정도 없다 — 이 저장소는 Graceful Shutdown을 전혀 구성하지 않은 상태다. JVM의 기본 종료 동작(SIGTERM 수신 즉시 진행 중인 요청을 끊고 프로세스 종료)에 노출되어 있다.

---

## `server.shutdown: graceful` — Spring Boot 내장 기능

Spring Boot 2.3+는 별도 라이브러리 없이 설정 한 줄로 graceful shutdown을 지원한다.

```yaml
# application.yml — 추가 필요
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # root의 terminationGracePeriodSeconds에 대응
```

SIGTERM 수신 시 Spring이 자동으로 수행하는 순서:

```
1. 내장 서버(Tomcat)가 새 요청 수신을 중단
2. 진행 중인 요청 처리 완료까지 대기 (최대 timeout-per-shutdown-phase)
3. ApplicationContext 종료 — @PreDestroy, DisposableBean 콜백 실행
4. 프로세스 종료
```

root가 강조하는 "readiness 전환이 HTTP 서버 종료보다 먼저"라는 순서를 이 내장 메커니즘이 프레임워크 차원에서 보장한다 — 별도의 `isShuttingDown` 플래그를 직접 구현할 필요가 없다.

---

## Liveness / Readiness 프로브 — Actuator

```groovy
// build.gradle — 추가 필요
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

```yaml
# application.yml — 추가
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
GET /actuator/health/liveness   → 200: 프로세스 생존 (종료 중에도 200 — 재시작 방지)
GET /actuator/health/readiness  → 200: 트래픽 수신 가능 / 503: 종료 중
```

Spring의 `AvailabilityChangeEvent`가 SIGTERM 수신과 `readinessState`를 자동으로 연동한다 — `graceful` shutdown이 시작되면 Spring이 `ReadinessState.REFUSING_TRAFFIC`로 전환해 `/actuator/health/readiness`가 즉시 503을 반환하고, `LivenessState`는 프로세스가 실제로 죽기 전까지 `CORRECT`(200)를 유지한다. root가 강조하는 "Liveness는 종료 중에도 항상 200" 원칙이 Actuator 기본 동작으로 이미 충족된다 — 애플리케이션 코드에서 두 상태를 직접 토글할 필요가 없다.

```yaml
# Kubernetes 예시
spec:
  terminationGracePeriodSeconds: 40   # spring.lifecycle.timeout-per-shutdown-phase(30s)보다 여유 있게
  containers:
    - livenessProbe:
        httpGet: { path: /actuator/health/liveness, port: 8080 }
      readinessProbe:
        httpGet: { path: /actuator/health/readiness, port: 8080 }
```

---

## 컨테이너에서 직접 프로세스 실행 — 이미 [container.md](container.md)에서 다룸

`ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]`(exec form)로 JVM이 PID 1이 되어야 SIGTERM을 즉시 수신한다 — `./gradlew bootRun` 같은 래퍼로 감싸면 Gradle 프로세스가 중간에 끼어 신호 전달이 지연되거나 유실된다. 상세는 [container.md](container.md) 참고.

---

## 리소스 정리 순서 — HikariCP 커넥션 풀

Spring Boot의 graceful shutdown 순서(HTTP 서버 종료 → `ApplicationContext` 종료)는 DataSource(HikariCP) 종료가 자연스럽게 마지막에 일어나도록 보장한다 — `DataSource` 빈은 Spring이 관리하는 다른 빈이므로, `ApplicationContext`가 완전히 종료되기 전까지는 진행 중인 요청이 DB 커넥션을 계속 사용할 수 있다. 별도의 수동 정리 순서 제어가 필요 없다.

`@PreDestroy`로 커스텀 정리 로직을 추가할 경우, 예외를 던지지 않도록 주의한다 — 정리 단계에서 예외가 발생하면 다른 `@PreDestroy` 콜백의 실행이 건너뛰어질 수 있다.

```java
// 커스텀 리소스가 있는 경우 (제안 — 이 저장소는 현재 해당 없음)
@PreDestroy
public void cleanup() {
    try {
        customResource.close();
    } catch (Exception e) {
        log.error("리소스 정리 실패", e);   // 예외를 던지지 않고 로깅만
    }
}
```

---

## 원칙

- **`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase` 필수 설정**: 현재 누락되어 있다.
- **Actuator의 liveness/readiness 프로브 활성화**: `management.health.livenessState/readinessState.enabled: true`.
- **오케스트레이터의 `terminationGracePeriodSeconds`는 `timeout-per-shutdown-phase`보다 여유 있게** 설정한다.
- **exec form ENTRYPOINT**: `["java", "..."]` — [container.md](container.md) 참고.
- **`@PreDestroy`에서 예외를 던지지 않는다**: try-catch로 감싸고 로그만 남긴다.

---

### 관련 문서

- [container.md](container.md) — Dockerfile ENTRYPOINT, Actuator 헬스체크
- [observability.md](observability.md) — 종료 관련 로깅
