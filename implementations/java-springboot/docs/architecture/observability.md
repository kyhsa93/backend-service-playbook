# Observability — 로깅, 메트릭, 트레이싱 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [observability.md](../../../../docs/architecture/observability.md) 참고.

## 현재 로깅 — SLF4J 일반 텍스트 로그 (구조화 안 됨)

이 저장소에 로깅이 실제로 존재하는 두 곳:

```java
// notification/infrastructure/NotificationServiceImpl.java — 실제 코드
private static final Logger log = LoggerFactory.getLogger(NotificationServiceImpl.class);
// ...
log.info("이메일 발송됨: accountId={}, eventType={}, recipient={}, sesMessageId={}",
        accountId, eventType, recipient, response.messageId());
```

```java
// account/application/event/AccountNotificationListener.java — 실제 코드
log.error("알림 이메일 발송 실패: accountId={}, eventType={}", accountId, eventType, e);
```

둘 다 SLF4J의 `{}` 플레이스홀더 문법을 쓰지만, 최종 출력은 사람이 읽기 위한 **평문 문자열**이다 — JSON도 아니고 필드명이 구조화되어 있지도 않다. 로그 수집기(CloudWatch, Datadog 등)가 `accountId`/`eventType`을 개별 필드로 인덱싱할 수 없다 — 문자열 전체를 파싱하거나 grep해야 한다.

---

## 구조화된 로깅 — Logback JSON 인코더 도입

```groovy
// build.gradle — 추가 필요
implementation 'net.logstash.logback:logstash-logback-encoder:7.4'
```

```xml
<!-- src/main/resources/logback-spring.xml — 신규 -->
<configuration>
    <springProfile name="local">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder><pattern>%d{HH:mm:ss} %-5level %logger{36} - %msg%n</pattern></encoder>
        </appender>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>

    <springProfile name="!local">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>correlation_id</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="JSON"/></root>
    </springProfile>
</configuration>
```

**로컬은 사람이 읽기 쉬운 평문, 운영은 JSON** — `springProfile`로 분기한다. JSON 인코더가 MDC의 `correlation_id`를 자동으로 로그 필드에 포함시킨다([cross-cutting-concerns.md](cross-cutting-concerns.md)의 `CorrelationIdFilter` 참고).

### 필드 네이밍 — snake_case로 구조화 인자 전달

```java
// 올바른 방식 — StructuredArguments로 키-값 쌍을 명시적으로 전달
import static net.logstash.logback.argument.StructuredArguments.kv;

log.info("이메일 발송됨", kv("account_id", accountId), kv("event_type", eventType), kv("ses_message_id", response.messageId()));
```

`StructuredArguments.kv(...)`는 로그 메시지 문자열에는 나타나지 않고 JSON 출력에만 별도 필드로 추가된다 — SLF4J의 `{}` 플레이스홀더와 달리 필드명을 명시적으로 통제할 수 있어 root의 "snake_case 필드명" 규칙을 강제하기 쉽다. 현재 `NotificationServiceImpl`/`AccountNotificationListener`의 `log.info("...: accountId={}, ...")` 호출을 이 방식으로 교체하는 것이 구조화 로깅 도입의 핵심 변경이다.

---

## 레이어별 로깅 기준

| 레이어 | 로깅 대상 | 이 저장소의 현재 상태 |
|--------|----------|------|
| Interfaces (Controller) | 요청 에러 | 없음 — `@ExceptionHandler`가 로깅 없이 바로 응답 변환 |
| Application (Service) | 비즈니스 이벤트, 외부 호출 결과 | 부분적 — `AccountNotificationListener`만 로깅, Command Service들은 로깅 없음 |
| Infrastructure | 외부 연동 실패/재시도 | `NotificationServiceImpl`(SES 발송 성공/실패) |
| Domain | **로깅 금지** | 준수 — `Account`는 로거를 import하지 않는다 |

**`AccountException`이 던져지는 지점에서 로깅이 없는 것은 개선 여지가 있다.** 현재 `@ExceptionHandler`는 예외를 잡아 바로 `ErrorResponse`로 변환할 뿐 로깅하지 않는다 — root 원칙("에러는 반드시 로깅한 뒤 예외를 던지거나 응답한다")에 따르면 최소한 `warn`/`error` 레벨로 기록해야 한다:

```java
// AccountController — 개선 (제안)
@ExceptionHandler(AccountException.class)
public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) {
    log.warn("계좌 요청 실패: code={}, message={}", e.code(), e.getMessage());
    // ... 기존 응답 변환
}
```

---

## Correlation ID — MDC 기반 전파

Correlation ID의 주입 위치(`Filter`)와 MDC 저장 방식은 [cross-cutting-concerns.md](cross-cutting-concerns.md)에서 다뤘다. 이후 모든 로깅 호출은 MDC에서 자동으로 값을 가져오므로(JSON 인코더 설정에 `includeMdcKeyName` 지정 시), 애플리케이션 코드가 매번 `correlationId` 파라미터를 명시적으로 전달할 필요가 없다 — root의 `AsyncLocalStorage.getStore()` 패턴을 Java의 `MDC.get(...)`가 대체한다.

```java
// 명시적으로 조회해야 할 때 (드묾 — 대부분 인코더가 자동 포함)
String correlationId = MDC.get("correlation_id");
```

---

## 메트릭 · 트레이싱 — Spring Boot Actuator + Micrometer

`build.gradle`에 Actuator/Micrometer 의존성이 아직 없다:

```groovy
// build.gradle — 추가 필요
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-prometheus'
```

```yaml
# application.yml — 추가
management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  metrics:
    tags:
      application: account-service
```

`GET /actuator/prometheus`가 Prometheus 스크레이프 엔드포인트를 자동 노출한다. HTTP 요청 수/지연시간(`http.server.requests`), JVM 메모리, DB 커넥션 풀(HikariCP) 지표가 별도 계측 코드 없이 자동 수집된다 — Spring Boot Actuator가 root의 "메트릭 스택은 특정하지 않되 Prometheus 권장" 방향을 프레임워크 차원에서 기본 지원한다.

트레이싱은 `spring-boot-starter-actuator` + Micrometer Tracing(`micrometer-tracing-bridge-otel`)으로 OpenTelemetry 연동이 가능하다 — 이 저장소는 아직 도입하지 않았다.

---

## 원칙

- **Domain 레이어에서 로깅 금지**: `Account`가 이미 준수하고 있다 — 신규 도메인 메서드 추가 시에도 유지한다.
- **구조화된 로그로 전환**: Logback JSON 인코더 + `StructuredArguments.kv(...)`로 snake_case 필드를 명시적으로 구성한다.
- **에러는 반드시 로깅**: 현재 `@ExceptionHandler`가 로깅 없이 바로 응답하는 지점을 보완한다.
- **Correlation ID는 MDC로 전파**: `Filter`가 주입, JSON 인코더가 자동 포함.
- **Actuator + Micrometer로 메트릭 노출**: 별도 계측 코드 없이 HTTP/JVM/DB 지표 확보.

---

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 주입 위치 (`Filter`)
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [graceful-shutdown.md](graceful-shutdown.md) — Actuator 헬스 프로브
