# Observability — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root observability.md](../../../../docs/architecture/observability.md) 참조.

## 현재 로깅 — SLF4J 직접 사용 + 구조화 로그(fluent API) 병행

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — 실제 코드
private val logger = LoggerFactory.getLogger(NotificationServiceImpl::class.java)

logger.atInfo()
    .addKeyValue("account_id", accountId)
    .addKeyValue("event_type", eventType)
    .addKeyValue("recipient", recipient)
    .addKeyValue("ses_message_id", result.messageId())
    .log("이메일 발송됨")
```

```kotlin
// outbox/OutboxRelay.kt — 실제 코드
.onFailure { logger.error("이벤트 처리 실패: eventType={}, eventId={}", row.eventType, row.eventId, it) }
```

```kotlin
// account/interfaces/rest/AccountController.kt — 실제 코드
logger.warn("계좌를 찾을 수 없음: {}", e.message)
```

`notification/infrastructure/NotificationServiceImpl`은 SLF4J 2.x fluent API(`atInfo().addKeyValue(...)`)로 이미 **snake_case 필드 기반 구조화 로그**를 남긴다 — `account_id`/`event_type` 등이 메시지 문자열에 파묻히지 않고 별도 key-value로 분리되어 있어 모니터링 시스템에서 필드별 검색·집계가 가능하다. `OutboxRelay`/`AccountController`는 아직 파라미터화된 텍스트 로그(`{}` 플레이스홀더)만 쓴다 — 문자열 보간(`"...${accountId}..."`)으로 인한 불필요한 문자열 생성은 피하지만, key-value 구조화까지는 하지 않았다. Correlation ID는 아래에서 다루는 `CorrelationIdFilter`가 MDC로 전파한다.

---

## SLF4J 직접 사용 vs `kotlin-logging` — 선택과 근거

이 저장소는 아직 `kotlin-logging` 같은 Kotlin 전용 로깅 라이브러리를 쓰지 않는다(`build.gradle.kts`에 의존성 없음). 두 방식을 비교한다.

| | `LoggerFactory.getLogger(X::class.java)` (현재) | `KotlinLogging.logger {}` |
|---|---|---|
| 선언 | `private val logger = LoggerFactory.getLogger(Foo::class.java)` | `private val logger = KotlinLogging.logger {}` (클래스명 자동 추론) |
| 메시지 평가 | 즉시 평가 — 로그 레벨이 꺼져 있어도 문자열 조합 비용 발생 가능 | 람다 지연 평가 — `logger.debug { "비싼 계산: ${expensive()}" }`이 debug 비활성 시 실행 자체가 안 됨 |
| 의존성 | 없음 (SLF4J는 Spring Boot 기본 포함) | `io.github.microutils:kotlin-logging-jvm` 추가 필요 |
| Kotlin다움 | `::class.java` 보일러플레이트 | 최상위 확장 프로퍼티로 클래스명 지정 불필요 |

**권장**: 새 코드는 `kotlin-logging`으로 전환한다. 지연 람다 평가가 `debug`/`trace` 레벨에서 실질적인 성능 이득을 주고, `::class.java` 반복을 없애 Kotlin다운 간결함을 얻는다. 기존 `LoggerFactory.getLogger(...)` 코드를 즉시 바꿀 필요는 없다 — 두 방식 모두 내부적으로 SLF4J API 위에서 동작하므로 공존 가능하다.

```kotlin
// build.gradle.kts — 추가 시
implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
```

```kotlin
// 전환 예시
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

logger.info { "이메일 발송됨: accountId=$accountId, eventType=$eventType" }   // 람다 — info 활성 시에만 문자열 생성
```

---

## 구조화된 로깅 — JSON + snake_case (logstash-logback-encoder, 적용 완료)

`build.gradle.kts`와 `logback-spring.xml` 모두 이미 JSON 인코더를 갖추고 있다 — prod 프로파일에서는 CloudWatch/Datadog 등으로 그대로 수집 가능한 JSON 로그를, 로컬/테스트에서는 사람이 읽기 좋은 텍스트 로그를 낸다.

```kotlin
// build.gradle.kts — 실제 코드
implementation("net.logstash.logback:logstash-logback-encoder:7.4")
```

```xml
<!-- logback-spring.xml — 실제 코드 -->
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

구조화 로그를 남길 때는 `Marker` 또는 key-value 쌍을 사용한다 — SLF4J 2.x부터는 fluent API로 필드를 명시적으로 붙일 수 있고, `NotificationServiceImpl`이 실제로 이 패턴을 쓴다(위 "현재 로깅" 절 참고). **필드명은 root 원칙대로 snake_case**(`account_id`, `event_type`)를 사용한다 — Kotlin 프로퍼티명은 camelCase(`accountId`)이지만, 로그 필드는 모니터링 플랫폼 관례에 맞춘다. `OutboxRelay`/`AccountController`처럼 아직 `{}` 플레이스홀더만 쓰는 로그는 필요에 따라 같은 fluent API로 승격할 수 있다(남은 개선 여지, 필수 갭은 아니다).

---

## Correlation ID — MDC 전파 (적용 완료)

[cross-cutting-concerns.md](cross-cutting-concerns.md)에서 다룬 `CorrelationIdFilter`(실제 코드, `common/CorrelationIdFilter.kt`)가 요청 진입 시 MDC에 값을 넣는다. 이후 애플리케이션 어디서든 `MDC.get("correlationId")`로 접근 가능하고, `logstash-logback-encoder`의 `includeMdcKeyName`이 자동으로 로그 필드에 포함시킨다 — 각 로그 호출에서 correlationId를 인자로 넘길 필요가 없다.

```kotlin
// 서비스 로직 — correlationId를 파라미터로 넘기지 않아도 로그에 자동 포함
logger.info { "계좌 생성 완료: accountId=$accountId" }
// 출력(JSON): {"message":"계좌 생성 완료...", "correlationId":"a1b2c3...", ...}
```

Node의 `AsyncLocalStorage`와 동일한 역할을 JVM 스레드 모델에서는 **MDC(`ThreadLocal` 기반)**가 담당한다 — Spring MVC가 요청당 스레드를 고정 배정하므로 자연스럽게 맞아떨어진다. 단, `@Async`나 별도 스레드 풀로 넘어가는 지점(코루틴, `CompletableFuture` 등)에서는 MDC가 자동으로 전파되지 않으므로 명시적으로 복사해야 한다 — 이 저장소는 현재 코루틴/비동기 처리를 쓰지 않으므로(→ [scheduling.md](scheduling.md)) 해당 문제가 없다.

---

## 레이어별 로깅 기준 — 이 저장소에서의 적용

| 레이어 | 현재 코드 | 평가 |
|---|---|---|
| `account/domain/` | 로깅 없음 | root 원칙과 일치 — Aggregate는 프레임워크 무의존 유지 |
| `outbox/OutboxRelay` | `logger.error(...)` (실패 시) | 일치 — Application 레이어에서 에러 로깅 |
| `notification/infrastructure/NotificationServiceImpl` | `logger.info(...)` (발송 성공) | 일치 — Infrastructure에서 외부 연동 결과 로깅 |
| `account/interfaces/rest/AccountController` | `logger.warn(...)` (`@ExceptionHandler`에서 실패 로깅) | 일치 — HTTP 요청/응답 자체의 로깅은 [cross-cutting-concerns.md](cross-cutting-concerns.md)의 `RequestLoggingInterceptor`(실제 코드, `common/RequestLoggingInterceptor.kt`)가 담당 |

---

## 원칙 요약

- **Domain 레이어에서 로깅 금지**: `Account`, `Money`, `Transaction`은 로거를 import하지 않는다.
- **구조화 로그는 `NotificationServiceImpl`에 이미 적용됨**: `logger.atInfo().addKeyValue(...)`로 snake_case 필드를 남긴다. `OutboxRelay`/`AccountController`의 `{}` 플레이스홀더 로그를 같은 방식으로 승격하는 것은 남은 개선 여지다.
- **`kotlin-logging` 도입 검토**: 지연 평가 + `::class.java` 보일러플레이트 제거 — 아직 도입하지 않았다.
- **MDC로 Correlation ID 전파(적용 완료)**: `CorrelationIdFilter`가 요청 진입점에서 설정하면 이후 모든 로그에 자동 포함.

### 관련 문서

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — Correlation ID 주입 필터
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
