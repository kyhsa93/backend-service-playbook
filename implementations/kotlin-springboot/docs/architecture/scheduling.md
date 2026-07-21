# 스케줄링 / 배치 작업 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root scheduling.md](../../../../docs/architecture/scheduling.md) 참조.

## 현재 상태 — `@Scheduled` 실제 구현 두 가지

이 저장소는 `@Scheduled`를 두 가지 서로 다른 목적으로 쓴다 — 둘 다 실제 코드다.

| 목적 | 구현 | 무엇을 나르는가 |
|---|---|---|
| Domain/Integration Event 발행 | `outbox/OutboxPoller.kt` | "사실: X가 일어났다" — Aggregate가 만든 이벤트 |
| Task Queue 적재/발행 | `account/infrastructure/scheduling/InterestPaymentScheduler.kt`, `card/infrastructure/scheduling/CardStatementScheduler.kt`, `taskqueue/TaskOutboxPoller.kt` | "명령: X를 수행하라" — Scheduler가 만든 배치 작업 지시 |

둘 다 [domain-events.md](domain-events.md)의 Task Queue vs Domain Event 구분을 그대로 따르는 별도 경로다 — 같은 `outbox`류 패턴(테이블 적재 → 독립 Poller가 SQS 발행 → 독립 Consumer가 수신)을 공유하지만, 큐도 다르고(`outbox_events`/도메인 이벤트 SQS 표준 큐 vs `task_outbox`/Task Queue SQS **FIFO** 큐) 라우팅 레지스트리도 다르다(`EventHandlerRegistry` 1:N vs `TaskHandlerRegistry` 1:1).

아래 절은 실제 구현(`정기 이자 지급`, `매월 카드 사용내역 발송` 두 Task)을 예시로 root scheduling.md의 각 패턴이 이 저장소에서 어떻게 코드로 나타나는지 설명한다.

---

## 이 저장소는 코루틴을 사용하지 않는다 — 이유

`build.gradle.kts`를 확인하면 `kotlinx-coroutines-core`나 `spring-boot-starter-webflux` 의존성이 없다. 이 예제는 **Spring MVC(서블릿 블로킹 스택) + Spring Data JPA(블로킹 드라이버)** 조합이다.

```kotlin
// build.gradle.kts — 실제 의존성
implementation("org.springframework.boot:spring-boot-starter-web")        // WebFlux 아님 — 블로킹 MVC
implementation("org.springframework.boot:spring-boot-starter-data-jpa")   // 블로킹 JDBC 드라이버 기반
```

이 조합에서는 `@Scheduled` 메서드가 **전통적인 스레드 풀 기반**으로 실행되는 것이 자연스럽다 — 코루틴(`suspend fun` + `kotlinx-coroutines`)을 도입해도 JPA 호출 자체가 블로킹이라 별도 디스패처(`Dispatchers.IO`)로 감싸야 하고, 이는 스레드 풀을 코루틴으로 한 번 더 감싸는 셈이라 실익이 적다. **코루틴은 WebFlux(리액티브 스택)나 넌블로킹 I/O(코루틴 기반 HTTP 클라이언트, R2DBC 등)와 결합될 때 진가를 발휘한다** — 이 저장소가 그 스택으로 전환하지 않는 한 도입 이유가 없다.

`taskqueue/TaskQueueConsumer.kt`도 `outbox/OutboxConsumer.kt`처럼 `waitTimeSeconds(5)` 동안 블로킹하며 무한히 반복하는 긴 루프이므로 `@Scheduled`가 아니라 전용 스레드(`SmartLifecycle`)로 표현한다 — domain-events.md 5단계 참고. `@Scheduled`는 "일정 주기로 짧게 실행하고 반환"하는 `OutboxPoller`/`TaskOutboxPoller`/`InterestPaymentScheduler`/`CardStatementScheduler`류 작업에만 쓴다.

---

## Scheduler 등록 — `@EnableScheduling` + 전용 스레드 풀

```kotlin
// AccountServiceApplication.kt — 실제 코드
@SpringBootApplication
@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class, SqsProperties::class)
@EnableScheduling // OutboxPoller의 @Scheduled(fixedDelay = 1000) 활성화
class AccountServiceApplication
```

기본 `@Scheduled`는 단일 스레드 풀(크기 1)에서 실행된다. Task Queue 도입 전에는 `OutboxPoller` 하나뿐이라 문제가 없었지만, 지금은 `OutboxPoller`/`TaskOutboxPoller`/`InterestPaymentScheduler`/`CardStatementScheduler` 4개가 `@Scheduled`를 쓰므로 서로를 블로킹하지 않도록 전용 `TaskScheduler` Bean으로 풀 크기를 늘렸다.

```kotlin
// taskqueue/SchedulingConfig.kt — 실제 코드
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
// account/infrastructure/scheduling/InterestPaymentScheduler.kt — 실제 코드(발췌)
@Component
class InterestPaymentScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    @Scheduled(cron = "0 0 3 * * *") // 매일 03:00
    fun enqueueDailyInterestPayment() {
        val payDate = LocalDate.now()
        val dedupId = "$TASK_TYPE-$payDate"
        runCatching {
            taskQueue.enqueue(
                taskType = TASK_TYPE,
                payload = objectMapper.writeValueAsString(mapOf("date" to payDate.toString())),
                groupId = TASK_TYPE,
                deduplicationId = dedupId,
            )
        }.onFailure { logger.atError().addKeyValue("pay_date", payDate.toString()).setCause(it).log("일일 이자 지급 Task 적재 실패") }
    }

    companion object { const val TASK_TYPE = "account.pay-interest" }
}
```

`card/infrastructure/scheduling/CardStatementScheduler.kt`(매월 1일 04:00, `card.send-statement`)도 같은 모양이다 — `payDate` 대신 `YearMonth.now()`를 payload로 넘긴다.

**날짜/월을 enqueue 시점에 확정해 payload로 넘긴다** — Task 처리(Consumer)가 지연되더라도 "몇 월 며칠자 작업인가"가 재계산되지 않는다. 이 값이 그대로 각 Task의 멱등성 키(`Account.lastInterestPaidAt`, `Card.lastStatementSentMonth`)로 쓰인다.

**Cron 예외는 명시적으로 로깅한다** — `@Scheduled` 메서드에서 던진 예외는 Spring이 로그만 남기고 다음 스케줄을 계속 실행한다(스케줄 자체가 죽지는 않지만, 예외 내용이 기본 로그 레벨에 묻히기 쉽다). `runCatching { }.onFailure { logger.error(...) }`로 명시적으로 잡아 로그를 남기는 것이 중요하다.

---

## Task Outbox — DB 변경과 Task 적재의 원자성

`taskqueue/TaskQueue.kt`(포트)와 `taskqueue/TaskOutboxWriter.kt`(구현체)가 root scheduling.md의 "Task Outbox 패턴"을 그대로 구현한다 — Scheduler는 SQS에 직접 발행하지 않고 `task_outbox` 테이블에 한 행을 적재할 뿐이다(dual-write 방지).

```kotlin
// taskqueue/TaskQueue.kt — 실제 코드(포트)
interface TaskQueue {
    fun enqueue(taskType: String, payload: String, groupId: String, deduplicationId: String)
}
```

```kotlin
// taskqueue/TaskOutboxWriter.kt — 실제 코드(발췌)
@Component
class TaskOutboxWriter(
    private val taskOutboxJpaRepository: TaskOutboxJpaRepository,
) : TaskQueue {
    override fun enqueue(taskType: String, payload: String, groupId: String, deduplicationId: String) {
        try {
            taskOutboxJpaRepository.save(TaskOutbox.create(taskType, payload, groupId, deduplicationId))
        } catch (e: DataIntegrityViolationException) {
            // deduplicationId UNIQUE 제약 위반 — 이미 적재됨(다중 인스턴스 안전성). 정상 상황이므로
            // info 로그만 남기고 조용히 넘어간다.
            logger.atInfo().addKeyValue("task_type", taskType).addKeyValue("deduplication_id", deduplicationId).log("이미 적재된 Task — 중복 enqueue 스킵")
        }
    }
}
```

Scheduler(Cron)에는 자연스러운 DB 트랜잭션이 없다 — `enqueue()` 하나(= Spring Data JPA `save()` 한 번)가 곧 원자적 단위다. `deduplicationId`에 DB 레벨 UNIQUE 제약을 걸어, SQS FIFO의 5분 중복 제거 윈도우보다 더 넓은 범위(여러 인스턴스가 몇 시간 간격으로 같은 tick을 실행해도)에서 다중 인스턴스 안전성을 보장한다.

이후 `taskqueue/TaskOutboxPoller.kt`(`@Scheduled(fixedDelay = 1000)`)가 독립적으로 `task_outbox`를 폴링해 SQS FIFO Task Queue에 발행하고(`MessageGroupId`/`MessageDeduplicationId` 포함), `taskqueue/TaskQueueConsumer.kt`(SmartLifecycle 전용 스레드)가 수신해 `TaskHandlerRegistry`로 라우팅한다 — `outbox/OutboxPoller.kt`/`OutboxConsumer.kt`와 완전히 같은 모양이며, 대상 큐와 라우팅 레지스트리만 다르다.

이 저장소에서는 사용자 Command 트랜잭션 안에서 Task를 적재하는 경우가 아직 없다(두 Task 모두 시스템/Scheduler가 발생시키는 배치 작업) — 그런 경우가 생기면 `taskOutboxJpaRepository`를 Command Service에 직접 주입하는 대신, `TaskQueue` 포트를 그대로 재사용해 `accountRepository.saveAccount(account)` 등과 같은 트랜잭션 경계 안에서 호출하면 된다(domain-events.md의 Outbox 패턴과 동일한 방식).

---

## Task Queue — SQS FIFO, Domain Event 큐와 분리

root scheduling.md/domain-events.md가 규정하는 "Task Queue(명령)와 Domain Event(사실)는 의미 단위가 다르다"는 구분을 이 저장소는 **별도 SQS 큐 + 별도 Consumer 컴포넌트**로 코드에도 드러낸다.

| | Domain/Integration Event 큐 | Task Queue |
|---|---|---|
| SQS 종류 | 표준 큐(`domain-events`) | **FIFO** 큐(`task-queue.fifo`) |
| 발행/수신 | `outbox/OutboxPoller.kt` / `outbox/OutboxConsumer.kt` | `taskqueue/TaskOutboxPoller.kt` / `taskqueue/TaskQueueConsumer.kt` |
| 라우팅 | `outbox/EventHandlerRegistry.kt`(eventType → 핸들러, 1:N) | `taskqueue/TaskHandlerRegistry.kt`(taskType → 핸들러, 1:1) |
| `SqsProperties` 필드 | `domainEventQueueUrl` | `taskQueueUrl` |

FIFO 큐가 필요한 이유: Task는 날짜/월 기반 `deduplicationId`(root scheduling.md "Cron 다중 인스턴스 안전성")로 중복 적재를 막아야 하는데, `MessageDeduplicationId`/`MessageGroupId`는 SQS FIFO 큐 전용 속성이다(표준 큐는 지원하지 않는다). `localstack/init-sqs.sh`가 `task-queue.fifo` + `task-queue-dlq.fifo`(둘 다 FIFO — AWS는 FIFO 큐의 DLQ도 FIFO여야 한다는 제약이 있다)를 만든다.

---

## Task Controller — Interface 레이어

```kotlin
// account/interfaces/task/PayInterestTaskController.kt — 실제 코드
@Component
class PayInterestTaskController(
    private val payInterestService: PayInterestService,
) {
    fun payInterest(payDate: LocalDate) {
        payInterestService.payInterest(payDate)   // 예외는 그대로 위로 전파
    }
}
```

`card/interfaces/task/SendCardStatementTaskController.kt`도 같은 모양이다. `taskqueue/TaskHandlerRegistry.kt`가 SQS payload(JSON)를 파싱해 이 Task Controller들을 호출한다 — `outbox/EventHandlerRegistry.kt`가 Domain Event Handler를 호출하는 것과 같은 책임 분담이다.

root 원칙대로 로직 없이 Command 위임만 하고, 예외를 그대로 던진다 — `TaskQueueConsumer`가 이를 잡아 메시지를 삭제하지 않고 SQS 재전달(at-least-once)에 맡긴다.

---

## Task 멱등성 — Level 1(본질적 멱등)을 Aggregate 필드로

두 Task 모두 **별도 Ledger 테이블 없이** Aggregate 자신의 필드로 Level 1 멱등성을 구현한다(domain-events.md 멱등성 3단계 참고).

```kotlin
// account/domain/Account.kt — 실제 코드(발췌)
var lastInterestPaidAt: LocalDate? = null
    private set

fun payInterest(payDate: LocalDate): Transaction? {
    if (status != AccountStatus.ACTIVE) return null
    if (lastInterestPaidAt == payDate) return null   // 같은 날짜면 이미 처리됨 — 아무 부작용 없이 스킵
    val interestAmount = /* balance * DAILY_INTEREST_RATE, floor */
    if (interestAmount <= 0) return null
    // ... balance 갱신, lastInterestPaidAt = payDate, Transaction(INTEREST) 생성, InterestPaidEvent 발행
}
```

```kotlin
// card/domain/Card.kt — 실제 코드(발췌)
var lastStatementSentMonth: String? = null
    private set

fun markStatementSent(yearMonth: String) {
    lastStatementSentMonth = yearMonth   // 상태 머신 전이가 아니라 단순 기록이라 예외를 던지지 않는다
}
```

`PayInterestService`/`SendMonthlyCardStatementsService`는 Repository 조회 단계에서부터 이미 처리된 대상을 걸러낸다(`AccountFindQuery.excludeInterestPaidDate`, `CardFindQuery.excludeStatementMonth`) — CancelCardsByAccountService가 `status IN (...)`으로 대상 카드를 걸러내는 것과 같은 "쿼리가 걸러내고, Aggregate 메서드는 방어적으로 재확인"하는 구조다.

카드 명세서는 추가로 Level 2(Ledger) 방어선을 하나 더 갖는다 — `NotificationService.sendEmail()`을 먼저 호출하고 성공한 뒤에야 `card.markStatementSent()`+저장을 하므로, 이메일 발송 자체의 `sourceEventId` 기반 중복 발송 방지(`notification/infrastructure/NotificationServiceImpl.kt`)가 Card 필드 갱신이 실패하는 경우의 안전망이 된다 — `SendMonthlyCardStatementsService`의 KDoc 참고.

---

## 원칙 요약

- **Scheduler는 Infrastructure 레이어**: `account/infrastructure/scheduling/`, `card/infrastructure/scheduling/`, `taskqueue/` 패키지에 배치. Application/Domain에 `@Scheduled` 사용 금지. harness `scheduler-in-infrastructure-only` 규칙이 `@Scheduled`/`@EnableScheduling`이 `domain/`·`application/`에 나타나면 실패시킨다.
- **코루틴 대신 전통적인 스레드 풀 스케줄링을 사용한다**: Spring MVC + JPA(블로킹 스택)와 자연스럽게 맞물리며, 이 저장소는 리액티브/코루틴 스택으로 전환하지 않았으므로 코루틴 도입 이유가 없다.
- **Scheduler는 적재만, Consumer가 실행**: 비즈니스 로직을 `@Scheduled` 메서드에 직접 넣지 않는다.
- **DB 변경과 Task 적재는 같은 트랜잭션(Task Outbox)**: dual-write 문제를 피한다. Cron처럼 트랜잭션 문맥이 없는 곳은 단일 row insert 자체가 원자적 단위다.
- **Task Queue는 Domain Event 큐와 분리**: FIFO 큐 + 전용 라우팅 레지스트리(`TaskHandlerRegistry`, 1:1)로 "명령"과 "사실"의 의미 단위 차이를 코드에도 드러낸다.
- **Task 멱등성은 Aggregate 필드로(Level 1)**: 가능하면 별도 Ledger 테이블 대신 "이미 처리했는가"를 Aggregate 스스로 알게 한다.
- **Cron 예외는 `runCatching` + 명시적 로깅**: 예외가 조용히 묻히지 않도록 한다.

### 관련 문서

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event, Outbox 패턴, 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — `@Transactional` 전파
- [graceful-shutdown.md](graceful-shutdown.md) — Scheduler/Consumer의 종료 시 대기 처리
