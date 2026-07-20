# 스케줄링 / 배치 작업 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root scheduling.md](../../../../docs/architecture/scheduling.md) 참조.

## 현재 상태 — `@Scheduled` 실제 구현: `OutboxPoller`

[domain-events.md](domain-events.md)의 `outbox/OutboxPoller.kt`가 이 문서가 아래에서 다루는
`@Scheduled(fixedDelay = ...)` + SQS 발행 방식을 **실제로 구현하고 있다**(2026-07 async
전환 — 이전에는 Command Service가 저장 직후 동기 호출해 드레인하는 `OutboxRelay`였다). 아래
절의 예시 코드는 대부분 `OutboxPoller.kt`/`OutboxConsumer.kt`의 실제 코드이며, 그 외의
Task Queue(만료 정리, 정산 등) 관련 예시는 여전히 가상 코드다 — 각 코드 블록의 헤더 주석이
실제/가상 여부를 명시한다.

---

## 이 저장소는 코루틴을 사용하지 않는다 — 이유

`build.gradle.kts`를 확인하면 `kotlinx-coroutines-core`나 `spring-boot-starter-webflux` 의존성이 없다. 이 예제는 **Spring MVC(서블릿 블로킹 스택) + Spring Data JPA(블로킹 드라이버)** 조합이다.

```kotlin
// build.gradle.kts — 실제 의존성
implementation("org.springframework.boot:spring-boot-starter-web")        // WebFlux 아님 — 블로킹 MVC
implementation("org.springframework.boot:spring-boot-starter-data-jpa")   // 블로킹 JDBC 드라이버 기반
```

이 조합에서는 `@Scheduled` 메서드가 **전통적인 스레드 풀 기반**으로 실행되는 것이 자연스럽다 — 코루틴(`suspend fun` + `kotlinx-coroutines`)을 도입해도 JPA 호출 자체가 블로킹이라 별도 디스패처(`Dispatchers.IO`)로 감싸야 하고, 이는 스레드 풀을 코루틴으로 한 번 더 감싸는 셈이라 실익이 적다. **코루틴은 WebFlux(리액티브 스택)나 넌블로킹 I/O(코루틴 기반 HTTP 클라이언트, R2DBC 등)와 결합될 때 진가를 발휘한다** — 이 저장소가 그 스택으로 전환하지 않는 한 도입 이유가 없다.

```kotlin
// outbox/OutboxPoller.kt — 실제 코드(발췌)
@Component
class OutboxPoller(private val outboxEventJpaRepository: OutboxEventJpaRepository /* ... */) {

    @Scheduled(fixedDelay = 1000)   // 스레드 풀에서 동기적으로 실행 — JPA 호출과 자연스럽게 맞물림
    @Transactional
    fun poll() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        pending.forEach { /* SQS 발행 (블로킹 SDK 호출) */ }
    }
}
```

`OutboxConsumer.kt`처럼 `waitTimeSeconds(5)` 동안 블로킹하며 무한히 반복하는 긴 루프는
`@Scheduled`로 표현하지 않고 전용 스레드(`SmartLifecycle`)를 쓴다 — domain-events.md 5단계 참고.
`@Scheduled`는 "일정 주기로 짧게 실행하고 반환"하는 `OutboxPoller`류 작업에만 쓴다.

---

## Scheduler 등록 — `@EnableScheduling` + 전용 스레드 풀

```kotlin
// AccountServiceApplication.kt — 추가 필요
@SpringBootApplication
@EnableScheduling
class AccountServiceApplication
```

기본 `@Scheduled`는 단일 스레드 풀(크기 1)에서 실행된다 — 여러 스케줄 작업이 있으면 서로를 블로킹할 수 있다. 전용 `TaskScheduler` Bean으로 풀 크기를 늘린다.

```kotlin
// scheduling/SchedulingConfig.kt — 제안
@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            setThreadNamePrefix("scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)   // graceful-shutdown.md 참조
            setAwaitTerminationSeconds(20)
        }
}
```

---

## Scheduler는 Infrastructure 레이어 — 적재만 한다

root 원칙대로, `@Scheduled` 메서드는 비즈니스 로직을 직접 실행하지 않고 **적재(enqueue)만** 한다. 실제 처리는 별도 Consumer/Command Service가 담당한다.

```kotlin
// outbox/OutboxPoller.kt — 실제 코드(발췌, 전체 코드는 domain-events.md 4단계)
@Component
class OutboxPoller(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun poll() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching {
                sqsClient.sendMessage(/* queueUrl, messageBody, messageAttributes(eventType/eventId) */)
            }.onSuccess { row.markProcessed() }
                .onFailure { logger.error("SQS 발행 실패: eventType=${row.eventType}", it) }
        }
    }
}
```

**Cron 예외는 명시적으로 로깅한다** — `@Scheduled` 메서드에서 던진 예외는 Spring이 로그만 남기고 다음 스케줄을 계속 실행한다(스케줄 자체가 죽지는 않지만, 예외 내용이 기본 로그 레벨에 묻히기 쉽다). `runCatching { }.onFailure { logger.error(...) }`로 명시적으로 잡아 로그를 남기는 것이 중요하다.

---

## 다중 인스턴스 안전성 — 날짜 기반 `deduplicationId`

여러 인스턴스가 동시에 배포되어 있으면 같은 Cron이 동시에 실행될 수 있다. FIFO 큐의 `deduplicationId`로 중복을 방지한다.

```kotlin
@Scheduled(cron = "0 0 3 * * *")   // 매일 03:00
fun enqueueDailyCleanup() {
    val dedupId = "account.cleanup-${LocalDate.now()}"
    runCatching {
        sqsClient.sendMessage {
            it.queueUrl(queueUrl)
                .messageBody("{}")
                .messageGroupId("account.cleanup")
                .messageDeduplicationId(dedupId)
        }
    }.onFailure { logger.error(it) { "일일 정리 작업 enqueue 실패" } }
}
```

---

## Task Outbox — DB 변경과 Task 적재의 원자성

Command Service에서 DB 변경과 함께 비동기 작업을 예약해야 한다면, 메시지 큐에 직접 발행하지 않고 같은 트랜잭션 안에서 `task_outbox` 테이블에 적재한다 — [domain-events.md](domain-events.md)의 Outbox 패턴과 동일한 이유(dual-write 문제 방지)다.

```kotlin
// application/command/CloseAccountService.kt — Task Outbox 적용 예시 (제안)
@Service
@Transactional
class CloseAccountService(
    private val accountRepository: AccountRepository,
    private val taskOutboxJpaRepository: TaskOutboxJpaRepository,
) {
    fun close(command: CloseAccountCommand) {
        val account = /* ... */
        account.close()
        accountRepository.saveAccount(account)
        taskOutboxJpaRepository.save(
            TaskOutbox.create(taskType = "account.archive", payload = """{"accountId":"${account.accountId}"}"""),
        )   // account 저장과 task 적재가 같은 @Transactional 안 — 원자적
    }
}
```

---

## 원칙 요약

- **Scheduler는 Infrastructure 레이어**: `outbox/`, `scheduling/` 패키지에 배치. Application/Domain에 `@Scheduled` 사용 금지.
- **코루틴 대신 전통적인 스레드 풀 스케줄링을 사용한다**: Spring MVC + JPA(블로킹 스택)와 자연스럽게 맞물리며, 이 저장소는 리액티브/코루틴 스택으로 전환하지 않았으므로 코루틴 도입 이유가 없다.
- **Scheduler는 적재만, Consumer가 실행**: 비즈니스 로직을 `@Scheduled` 메서드에 직접 넣지 않는다.
- **DB 변경과 Task 적재는 같은 트랜잭션(Task Outbox)**: dual-write 문제를 피한다.
- **Cron 예외는 `runCatching` + 명시적 로깅**: 예외가 조용히 묻히지 않도록 한다.

### 관련 문서

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event, Outbox 패턴, 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — `@Transactional` 전파
- [graceful-shutdown.md](graceful-shutdown.md) — Scheduler의 종료 시 대기 처리
