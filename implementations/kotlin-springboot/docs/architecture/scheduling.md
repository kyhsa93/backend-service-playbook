# Scheduling / Batch Jobs — Kotlin Spring Boot

> For the framework-agnostic principles, see [root scheduling.md](../../../../docs/architecture/scheduling.md).

## Current state — two real `@Scheduled` implementations

This repository uses `@Scheduled` for two different purposes — both are actual code.

| Purpose | Implementation | What it carries |
|---|---|---|
| Publishing Domain/Integration Events | `outbox/OutboxPoller.kt` | "a fact: X happened" — an event the Aggregate produced |
| Writing/publishing Task Queue entries | `account/infrastructure/scheduling/InterestPaymentScheduler.kt`, `card/infrastructure/scheduling/CardStatementScheduler.kt`, `taskqueue/TaskOutboxPoller.kt` | "a command: do X" — a batch-job instruction the Scheduler produced |

Both are separate paths that follow the Task Queue vs. Domain Event distinction from [domain-events.md](domain-events.md) exactly — they share the same `outbox`-style pattern (write to a table → an independent Poller publishes to SQS → an independent Consumer receives), but the queue is different (`outbox_events`/the domain-event SQS **standard** queue vs. `task_outbox`/the Task Queue SQS **FIFO** queue), and so is the routing registry (`EventHandlerRegistry`, 1:N, vs. `TaskHandlerRegistry`, 1:1).

The section below uses the actual implementation (the two Tasks "pay interest periodically" and "send the monthly card statement") as examples to explain how each pattern from the root scheduling.md shows up as code in this repository.

---

## This repository doesn't use coroutines — why

Checking `build.gradle.kts` shows no `kotlinx-coroutines-core` or `spring-boot-starter-webflux` dependency. This example is a **Spring MVC (the servlet blocking stack) + Spring Data JPA (a blocking driver)** combination.

```kotlin
// build.gradle.kts — actual dependencies
implementation("org.springframework.boot:spring-boot-starter-web")        // not WebFlux — blocking MVC
implementation("org.springframework.boot:spring-boot-starter-data-jpa")   // based on a blocking JDBC driver
```

In this combination, it's natural for `@Scheduled` methods to run on a **traditional thread-pool basis** — even if coroutines (`suspend fun` + `kotlinx-coroutines`) were introduced, the JPA call itself is blocking, so it would still need wrapping in a separate dispatcher (`Dispatchers.IO`), which is really just wrapping a thread pool in coroutines one more time, with little real benefit. **Coroutines show their real value when combined with WebFlux (the reactive stack) or non-blocking I/O (a coroutine-based HTTP client, R2DBC, etc)** — there's no reason to adopt them unless this repository switches to that stack.

`taskqueue/TaskQueueConsumer.kt`, like `outbox/OutboxConsumer.kt`, is a single long loop that blocks for `waitTimeSeconds(5)` and repeats indefinitely, so it's expressed as a dedicated thread (`SmartLifecycle`) rather than `@Scheduled` — see domain-events.md step 5. `@Scheduled` is only used for work like `OutboxPoller`/`TaskOutboxPoller`/`InterestPaymentScheduler`/`CardStatementScheduler` that "runs briefly on a fixed interval and returns."

---

## Registering the Scheduler — `@EnableScheduling` + a dedicated thread pool

```kotlin
// AccountServiceApplication.kt — actual code
@SpringBootApplication
@EnableConfigurationProperties(AwsProperties::class, SesProperties::class, JwtProperties::class, SqsProperties::class)
@EnableScheduling // enables OutboxPoller's @Scheduled(fixedDelay = 1000)
class AccountServiceApplication
```

By default, `@Scheduled` runs on a single thread pool (size 1). Before the Task Queue was introduced, there was only `OutboxPoller`, so this wasn't a problem, but now that 4 components — `OutboxPoller`/`TaskOutboxPoller`/`InterestPaymentScheduler`/`CardStatementScheduler` — use `@Scheduled`, the pool size was increased via a dedicated `TaskScheduler` Bean so they don't block each other.

```kotlin
// taskqueue/SchedulingConfig.kt — actual code
@Configuration
class SchedulingConfig {
    @Bean
    fun taskScheduler(): TaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 4
            setThreadNamePrefix("scheduled-")
            setWaitForTasksToCompleteOnShutdown(true)   // see graceful-shutdown.md
            setAwaitTerminationSeconds(20)
        }
}
```

---

## A Scheduler is an Infrastructure-layer thing — it only enqueues

Per the root principle, a `@Scheduled` method never directly executes business logic — it **only enqueues**. The actual processing is handled by a separate Consumer/Command Service.

```kotlin
// account/infrastructure/scheduling/InterestPaymentScheduler.kt — actual code (excerpt)
@Component
class InterestPaymentScheduler(
    private val taskQueue: TaskQueue,
    private val objectMapper: ObjectMapper,
) {
    @Scheduled(cron = "0 0 3 * * *") // every day at 03:00
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
        }.onFailure { logger.atError().addKeyValue("pay_date", payDate.toString()).setCause(it).log("Failed to enqueue the daily interest payment task") }
    }

    companion object { const val TASK_TYPE = "account.pay-interest" }
}
```

`card/infrastructure/scheduling/CardStatementScheduler.kt` (1st of the month at 04:00, `card.send-statement`) looks the same — it passes `YearMonth.now()` as the payload instead of `payDate`.

**The date/month is fixed at enqueue time and passed as the payload** — even if Task processing (the Consumer) is delayed, "which day/month this work is for" is never recomputed. This value is used as-is as each Task's idempotency key (`Account.lastInterestPaidAt`, `Card.lastStatementSentMonth`).

**Cron exceptions are logged explicitly** — an exception thrown from a `@Scheduled` method is just logged by Spring, which then keeps running the next schedule (the schedule itself doesn't die, but the exception content can easily get buried at the default log level). Explicitly catching it with `runCatching { }.onFailure { logger.error(...) }` and logging it is important.

---

## The Task Outbox — atomicity between the DB change and writing the Task

`taskqueue/TaskQueue.kt` (the port) and `taskqueue/TaskOutboxWriter.kt` (the implementation) implement the root scheduling.md's "Task Outbox pattern" as-is — the Scheduler never publishes directly to SQS; it just writes one row to the `task_outbox` table (avoiding dual-write).

```kotlin
// taskqueue/TaskQueue.kt — actual code (port)
interface TaskQueue {
    fun enqueue(taskType: String, payload: String, groupId: String, deduplicationId: String)
}
```

```kotlin
// taskqueue/TaskOutboxWriter.kt — actual code (excerpt)
@Component
class TaskOutboxWriter(
    private val taskOutboxJpaRepository: TaskOutboxJpaRepository,
) : TaskQueue {
    override fun enqueue(taskType: String, payload: String, groupId: String, deduplicationId: String) {
        try {
            taskOutboxJpaRepository.save(TaskOutbox.create(taskType, payload, groupId, deduplicationId))
        } catch (e: DataIntegrityViolationException) {
            // Violates the deduplicationId UNIQUE constraint — already enqueued (multi-instance
            // safety). This is a normal situation, so just log at info level and move on quietly.
            logger.atInfo().addKeyValue("task_type", taskType).addKeyValue("deduplication_id", deduplicationId).log("Task already enqueued — skipping duplicate enqueue")
        }
    }
}
```

There's no natural DB transaction around a Scheduler (Cron) — a single `enqueue()` call (= a single Spring Data JPA `save()`) is itself the atomic unit. Putting a DB-level UNIQUE constraint on `deduplicationId` guarantees multi-instance safety over a wider range than SQS FIFO's 5-minute dedup window (even if multiple instances run the same tick hours apart).

Afterward, `taskqueue/TaskOutboxPoller.kt` (`@Scheduled(fixedDelay = 1000)`) independently polls `task_outbox` and publishes to the SQS FIFO Task Queue (including `MessageGroupId`/`MessageDeduplicationId`), and `taskqueue/TaskQueueConsumer.kt` (a dedicated `SmartLifecycle` thread) receives it and routes it via `TaskHandlerRegistry` — this looks exactly like `outbox/OutboxPoller.kt`/`OutboxConsumer.kt`, differing only in the target queue and routing registry.

This repository doesn't yet have a case where a Task is written inside a user Command's transaction (both current Tasks are batch jobs triggered by the system/Scheduler) — if such a case arises, instead of injecting `taskOutboxJpaRepository` directly into a Command Service, you'd reuse the `TaskQueue` port as-is and call it inside the same transaction boundary as `accountRepository.saveAccount(account)`, etc (the same approach as the Outbox pattern in domain-events.md).

---

## The Task Queue — SQS FIFO, kept separate from the Domain Event queue

The distinction the root scheduling.md/domain-events.md specifies — "the Task Queue (a command) and a Domain Event (a fact) are different units of meaning" — is made visible in this repository's code too, via **a separate SQS queue + a separate Consumer component**.

| | The Domain/Integration Event queue | The Task Queue |
|---|---|---|
| SQS type | standard queue (`domain-events`) | **FIFO** queue (`task-queue.fifo`) |
| Publish/receive | `outbox/OutboxPoller.kt` / `outbox/OutboxConsumer.kt` | `taskqueue/TaskOutboxPoller.kt` / `taskqueue/TaskQueueConsumer.kt` |
| Routing | `outbox/EventHandlerRegistry.kt` (eventType → handler, 1:N) | `taskqueue/TaskHandlerRegistry.kt` (taskType → handler, 1:1) |
| `SqsProperties` field | `domainEventQueueUrl` | `taskQueueUrl` |

Why a FIFO queue is needed: a Task must prevent duplicate writes using a date/month-based `deduplicationId` (root scheduling.md's "Cron multi-instance safety"), and `MessageDeduplicationId`/`MessageGroupId` are properties exclusive to an SQS FIFO queue (a standard queue doesn't support them). `localstack/init-sqs.sh` creates `task-queue.fifo` + `task-queue-dlq.fifo` (both FIFO — AWS has a constraint that a FIFO queue's DLQ must also be FIFO).

---

## The Task Controller — the Interface layer

```kotlin
// account/interfaces/task/PayInterestTaskController.kt — actual code
@Component
class PayInterestTaskController(
    private val payInterestService: PayInterestService,
) {
    fun payInterest(payDate: LocalDate) {
        payInterestService.payInterest(payDate)   // the exception propagates upward as-is
    }
}
```

`card/interfaces/task/SendCardStatementTaskController.kt` looks the same. `taskqueue/TaskHandlerRegistry.kt` parses the SQS payload (JSON) and calls these Task Controllers — the same division of responsibility as `outbox/EventHandlerRegistry.kt` calling a Domain Event Handler.

Per the root principle, it has no logic of its own and just delegates to the Command, letting the exception propagate as-is — `TaskQueueConsumer` catches it and, instead of deleting the message, leaves it to SQS redelivery (at-least-once).

---

## Task idempotency

Both Tasks implement Level 1 idempotency (intrinsic idempotency) using the Aggregate's own field, **with no separate Ledger table** (see the 3-tier idempotency model in domain-events.md).

```kotlin
// account/domain/Account.kt — actual code (excerpt)
var lastInterestPaidAt: LocalDate? = null
    private set

fun payInterest(payDate: LocalDate): Transaction? {
    if (status != AccountStatus.ACTIVE) return null
    if (lastInterestPaidAt == payDate) return null   // same date means already processed — skip with no side effect
    val interestAmount = /* balance * DAILY_INTEREST_RATE, floor */
    if (interestAmount <= 0) return null
    // ... update balance, lastInterestPaidAt = payDate, create a Transaction(INTEREST), publish InterestPaidEvent
}
```

```kotlin
// card/domain/Card.kt — actual code (excerpt)
var lastStatementSentMonth: String? = null
    private set

fun markStatementSent(yearMonth: String) {
    lastStatementSentMonth = yearMonth   // never throws, since this is a plain record rather than a state-machine transition
}
```

`PayInterestService`/`SendMonthlyCardStatementsService` already filter out already-processed targets at the Repository query stage (`AccountFindQuery.excludeInterestPaidDate`, `CardFindQuery.excludeStatementMonth`) — the same structure as `CancelCardsByAccountService` filtering target cards with `status IN (...)`: "the query filters, and the Aggregate method double-checks defensively."

The card statement has one more Level 2 (Ledger) line of defense on top of that — it calls `NotificationService.sendEmail()` first, and only after that succeeds does it call `card.markStatementSent()` + save, so the email send's own `sourceEventId`-based duplicate-send prevention (`notification/infrastructure/NotificationServiceImpl.kt`) acts as a safety net for the case where updating the Card field fails — see the KDoc on `SendMonthlyCardStatementsService`.

---

## Principle summary

- **A Scheduler is an Infrastructure-layer thing**: placed in the `account/infrastructure/scheduling/`, `card/infrastructure/scheduling/`, `taskqueue/` packages. Using `@Scheduled` in Application/Domain is forbidden. The harness's `scheduler-in-infrastructure-only` rule fails if `@Scheduled`/`@EnableScheduling` appears in `domain/`·`application/`.
- **Traditional thread-pool scheduling is used instead of coroutines**: it naturally meshes with Spring MVC + JPA (the blocking stack), and since this repository hasn't switched to a reactive/coroutine stack, there's no reason to adopt coroutines.
- **A Scheduler only enqueues; the Consumer executes**: business logic is never placed directly in a `@Scheduled` method.
- **The DB change and writing the Task share the same transaction (the Task Outbox)**: this avoids the dual-write problem. Somewhere with no transaction context, like Cron, a single row insert is itself the atomic unit.
- **The Task Queue is kept separate from the Domain Event queue**: a FIFO queue + a dedicated routing registry (`TaskHandlerRegistry`, 1:1) makes the difference in meaning between a "command" and a "fact" visible in the code too.
- **Task idempotency is via an Aggregate field (Level 1)**: where possible, the Aggregate itself knows "has this already been processed," instead of a separate Ledger table.
- **Cron exceptions use `runCatching` + explicit logging**: so an exception is never silently buried.

### Related documents

- [domain-events.md](domain-events.md) — Task Queue vs. Domain Event, the Outbox pattern, the 3-tier idempotency model
- [layer-architecture.md](layer-architecture.md) — `@Transactional` propagation
- [graceful-shutdown.md](graceful-shutdown.md) — how the Scheduler/Consumer wait on shutdown
