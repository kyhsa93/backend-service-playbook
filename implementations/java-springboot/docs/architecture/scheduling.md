# Scheduling / Batch Jobs (Spring Boot)

> For the framework-agnostic principles, see the root [scheduling.md](../../../../docs/architecture/scheduling.md).

## Current state — genuinely implemented

`examples/` has two real periodic business batches — both implement the root's `Scheduler → task_outbox → Relay → message queue → Consumer → TaskController → CommandService` path literally:

1. **Periodic interest payment** (Feature 1) — every day at 3 AM, pays 0.01% interest on the balance of every ACTIVE account. `account/infrastructure/scheduling/InterestPaymentScheduler` → `account/interfaces/task/PayInterestTaskController` → `account/application/command/PayInterestService` → `Account.payInterest()` (Level 1 inherently idempotent, via `lastInterestPaidAt`).
2. **Monthly card-statement sending** (Feature 2) → `card/infrastructure/scheduling/CardStatementScheduler` → `card/interfaces/task/SendCardStatementTaskController` → `card/application/command/SendCardStatementService` → aggregates Payment BC usage data (ACL) + sends via SES, `Card.markStatementSent()` (Level 2 Ledger, via `lastStatementSentMonth`).

Both Schedulers write via `taskqueue/TaskOutboxWriter` (a shared infrastructure package, sibling to `outbox/`), which `taskqueue/TaskOutboxPoller` (`@Scheduled(fixedDelay=1000)`) drains and publishes to a Task Queue (SQS **FIFO**), and `taskqueue/TaskConsumer` (a dedicated background thread under `SmartLifecycle`) receives and invokes the `TaskHandler` registered for that `taskType` (each domain's Task Controller). This is a completely separate queue and separate table (`task_outbox`) from the Domain/Integration Event queue (`outbox/`, a standard queue) — because a Task (a command: "do X") and a Domain Event (a fact: "X happened") are conceptually different (see [domain-events.md](domain-events.md)).

---

## Minimum requirements

1. **A Scheduler is placed in the Infrastructure layer** — `@Component` + `@Scheduled`, in `<domain>/infrastructure/scheduling/`, not `application/`. Both `InterestPaymentScheduler`/`CardStatementScheduler` are placed there.
2. **A Task handler is idempotent** — `Account.payInterest()` (Level 1) and `Card.markStatementSent()` (Level 2) are real examples.
3. **If a message queue is used, a DLQ is the default** — `task-queue-dlq.fifo` + `maxReceiveCount=3`.

---

## The actual Task Outbox path

Since the caller is a Scheduler (Cron) with no Command transaction context, the root's principle of "the DB change and the Task write in the same transaction" here takes the form where **a single row insert is itself the atomic unit of writing** (one line: `taskOutboxRepository.save()`):

```java
// account/infrastructure/scheduling/InterestPaymentScheduler.java — actual code
@Component
@RequiredArgsConstructor
public class InterestPaymentScheduler {

    private static final String TASK_TYPE = "account.pay-interest";
    private static final String GROUP_ID = "account.interest";

    private final TaskOutboxWriter taskOutboxWriter;

    @Scheduled(cron = "0 0 3 * * *") // every day at 3 AM
    public void enqueueDailyInterestPayment() {
        try {
            LocalDate today = LocalDate.now();
            String dedupId = TASK_TYPE + "-" + today;
            taskOutboxWriter.enqueue(TASK_TYPE, new Payload(today), GROUP_ID, dedupId);
        } catch (Exception e) {
            log.error("Failed to write the interest-payment Task", e);
            // never rethrown — retried on the next tick (3 AM the following day).
        }
    }

    private record Payload(LocalDate date) {}
}
```

```java
// taskqueue/TaskOutboxWriter.java — actual code
@Component
@RequiredArgsConstructor
public class TaskOutboxWriter {
    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void enqueue(String taskType, Object payload, String groupId, String deduplicationId) {
        try {
            taskOutboxJpaRepository.save(TaskOutboxEntry.create(
                    taskType, objectMapper.writeValueAsString(payload), groupId, deduplicationId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Task payload: " + taskType, e);
        }
    }
}
```

A regular Command Service (one with a transaction context, like the root example's `CloseAccountService`) calling this same `TaskOutboxWriter.enqueue()` inside a `@Transactional` method would bundle the Aggregate save and the Task write into the same physical transaction — currently, both Features are called only from a Scheduler, so this repository doesn't yet have an example of that combination.

`taskqueue/TaskOutboxEntry` (`@Entity`, the `task_outbox` table) has the same structure as `outbox/OutboxEvent` but with `groupId`/`deduplicationId` columns added — because these are passed straight through as the FIFO queue's `MessageGroupId`/`MessageDeduplicationId` (see "Cron multi-instance safety" below).

---

## `TaskOutboxPoller`/`TaskConsumer` — the same structure as `outbox/OutboxPoller`/`OutboxConsumer`

```java
// taskqueue/TaskOutboxPoller.java — actual code (excerpt)
@Component
@RequiredArgsConstructor
public class TaskOutboxPoller {

    private final TaskOutboxJpaRepository taskOutboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    @Scheduled(fixedDelay = 1000)
    @Transactional   // needed for the same reason as outbox/OutboxPoller, since payload is an @Lob column
    public void poll() {
        List<TaskOutboxEntry> pending = taskOutboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (TaskOutboxEntry entry : pending) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(sqsProperties.taskQueueUrl())
                        .messageBody(entry.getPayload())
                        .messageGroupId(entry.getGroupId())              // FIFO-only
                        .messageDeduplicationId(entry.getDeduplicationId())  // FIFO-only
                        .messageAttributes(Map.of("taskType", MessageAttributeValue.builder()
                                .dataType("String").stringValue(entry.getTaskType()).build()))
                        .build());
                entry.markProcessed();
                taskOutboxJpaRepository.save(entry);
            } catch (Exception e) {
                log.error("Task Queue publish failed", kv("task_type", entry.getTaskType()), e);
                // stays processed=false and is retried on the next tick.
            }
        }
    }
}
```

```java
// taskqueue/TaskConsumer.java — actual code (excerpt)
@Component
public class TaskConsumer implements SmartLifecycle {

    private final Map<String, TaskHandler> handlers;   // taskType() -> handler, built once in the constructor
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "task-consumer"));
    private volatile boolean running = false;

    public TaskConsumer(SqsClient sqsClient, SqsProperties sqsProperties, List<TaskHandler> taskHandlers) {
        this.handlers = taskHandlers.stream()
                .collect(Collectors.toMap(TaskHandler::taskType, Function.identity()));
    }

    @Override public void start() { running = true; executor.submit(this::pollLoop); }
    @Override public void stop() { /* the same graceful shutdown as outbox/OutboxConsumer */ }

    private void handleMessage(String queueUrl, Message message) {
        String taskType = /* message.messageAttributes().get("taskType").stringValue() */ null;
        try {
            TaskHandler handler = handlers.get(taskType);
            if (handler == null) throw new IllegalStateException("No registered Task Handler for: " + taskType);
            handler.handle(message.body());
            sqsClient.deleteMessage(/* ack */);
        } catch (Exception e) {
            log.error("Task handling failed", kv("task_type", taskType), e);
            // not deleted — re-received and retried after the visibility timeout.
        }
    }
}
```

For exactly the same reason as `outbox/OutboxConsumer`, this uses a dedicated single-thread `ExecutorService` + `SmartLifecycle` rather than `@Scheduled` — loading `waitTimeSeconds(5)`'s blocking long polling onto the `@Scheduled` thread pool risks delaying the execution of other scheduled tasks like `TaskOutboxPoller`/`OutboxPoller` (for the detailed rationale, see [domain-events.md — Background-loop design](domain-events.md#background-loop-design--why-smartlifecycle--a-dedicated-thread-instead-of-scheduled)).

**Handler routing is `Collectors.toMap` (exactly one handler per taskType).** Unlike `outbox/OutboxConsumer`, which needs to support registering multiple handlers per event type (via `Collectors.groupingBy`) since more than one BC can react to the same Domain/Integration Event, a Task always has exactly one Task Controller decide "who performs this command" (a Task Controller is exactly one endpoint, just like an HTTP Controller) — there's no conceptual reason for multiple handlers to compete over the same `taskType`, so `toMap` is the right design here.

---

## Task Controller — the Interface layer, actual code

```java
// account/interfaces/task/PayInterestTaskController.java — actual code
@Component
@RequiredArgsConstructor
public class PayInterestTaskController implements TaskHandler {

    private final PayInterestService payInterestService;
    private final ObjectMapper objectMapper;

    @Override
    public String taskType() {
        return "account.pay-interest";
    }

    @Override
    public void handle(String payload) throws Exception {
        Payload parsed = objectMapper.readValue(payload, Payload.class);
        payInterestService.payInterest(new PayInterestCommand(parsed.date()));
        // the exception is rethrown as-is — never caught/converted. TaskConsumer decides on retry/DLQ.
    }

    private record Payload(LocalDate date) {}
}
```

Exactly like the `HTTP Controller ↔ CommandService` relationship, a Task Controller only delegates — with no conditional branching or business rules. **There is no catch/error-response-conversion layer like an HTTP Controller's `@RestControllerAdvice` (`GlobalExceptionHandler`)** — that's the core of the root principle ("throw the error as-is"). Both `PayInterestTaskController`/`SendCardStatementTaskController` are a single line calling a CommandService, with no `try`/`catch`.

The payload is defined by the Scheduler and the Task Controller as separate local `record`s that only agree on field names (they don't share a type) — because if `infrastructure` referenced a type from `interfaces`, it would break the layer dependency direction (Interface → Application, and Infrastructure → Domain/Application, never a reference to Interface).

---

## Cron multi-instance safety — a FIFO queue + date/month-based dedup

This repository genuinely adopts **FIFO dedup** among the root's two proposed options (FIFO dedup vs. ShedLock) — since SQS is already used via the `outbox/` pattern, it's a natural fit with no added infrastructure. Only the Task Queue is FIFO; the Domain Event queue (`outbox/`) remains a standard queue (since their purposes differ).

| Scheduler | dedupId | groupId |
|---|---|---|
| `InterestPaymentScheduler` | `account.pay-interest-<today's date>` | `account.interest` |
| `CardStatementScheduler` | `card.send-statement-<this month>` | `card.statement` |

Even if multiple instances tick at the same time on the same day/month, multiple rows can still accumulate in `task_outbox` (each instance inserts independently), but when `TaskOutboxPoller` publishes, only 1 message per identical `deduplicationId` makes it into the queue within SQS FIFO's 5-minute dedup window. In extreme cases that aren't filtered at the queue level (a retry outside the window, etc.), the Level 1/Level 2 idempotency of `Account.payInterest()`/`Card.markStatementSent()` is the final line of defense — the two layers together guarantee multi-instance safety.

For a simple batch that hasn't yet adopted a queue, `ShedLock` (`net.javacrumbs.shedlock`) also remains a valid alternative — this repository doesn't adopt it, but it remains a choosable option depending on the infrastructure situation.

---

## Idempotency — actual Level 1 / Level 2 examples

```java
// account/domain/Account.java — actual code (excerpt), Level 1 — inherently idempotent
public Optional<Transaction> payInterest(LocalDate today) {
    if (this.status != AccountStatus.ACTIVE) return Optional.empty();
    if (this.lastInterestPaidAt != null && !this.lastInterestPaidAt.isBefore(today)) {
        return Optional.empty();   // no-op if already paid today
    }
    long interestAmount = this.balance.amount() / DAILY_INTEREST_RATE_DENOMINATOR;
    if (interestAmount <= 0) return Optional.empty();   // computing zero interest is also a no-op — always the same result even if re-run
    // ... increase balance + update lastInterestPaidAt + create Transaction ...
}
```

```java
// card/domain/Card.java — actual code (excerpt), Level 2 — Ledger
public boolean shouldSendStatement(YearMonth month) {
    return this.status == CardStatus.ACTIVE && !month.equals(this.lastStatementSentMonth);
}

public void markStatementSent(YearMonth month) {
    this.lastStatementSentMonth = month;
}
```

`SendCardStatementService` skips both the email send and `cardRepository.saveCard()` for a card where `shouldSendStatement()` is `false` — so if the same Task is re-run under at-least-once delivery, it doesn't resend. Neither approach uses a separate "processing record" table (a ledger for Level 3 strong atomicity) — it's inlined into an Aggregate field instead, because the Aggregate's own state (today's date, this month) is sufficient as the judgment criterion. This follows the same 3-level model as event-handler idempotency in `domain-events.md`.

---

## DLQ — actual SQS configuration

```bash
# localstack/init-sqs.sh — actual code (excerpt)
awslocal sqs create-queue --queue-name task-queue-dlq.fifo \
  --attributes '{"FifoQueue":"true"}'

TASK_DLQ_ARN=$(awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/task-queue-dlq.fifo \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

awslocal sqs create-queue --queue-name task-queue.fifo \
  --attributes '{"FifoQueue":"true","RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$TASK_DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

The same configuration (`maxReceiveCount=3`) as `outbox/`'s `domain-events-dlq`, with the FIFO queue attribute (`FifoQueue: true`) added. `SQS_TASK_QUEUE_URL` in `.env.example`/`docker-compose.yml` and `config/SqsProperties.taskQueueUrl()` (`@NotBlank`) inject the actual URL.

---

## Principles

- **A Scheduler is in the Infrastructure layer**: the `<domain>/infrastructure/scheduling/` package — `@Scheduled` in Application/Domain is forbidden — verified by `harness/src/rules/SchedulerInInfrastructureOnly.java` (see below).
- **A Scheduler only writes**: a `@Scheduled` method only calls `TaskOutboxWriter.enqueue()` — all business logic (interest calculation, usage-statistics aggregation, etc.) lives entirely after the Task Controller (CommandService/Aggregate).
- **A Task Controller is in the Interface layer**: `<domain>/interfaces/task/`, the same kind of input adapter as an HTTP Controller. It throws errors as-is (no catch/conversion).
- **Writing goes through the Task Outbox**: the `task_outbox` table → `TaskOutboxPoller` → SQS FIFO → `TaskConsumer`. A completely separate table and queue from the Domain Event Outbox (`outbox/`).
- **A Task must be idempotent**: both `Account.payInterest()` (Level 1) and `Card.markStatementSent()` (Level 2) are genuinely implemented, and E2E tests (`InterestPaymentSchedulingE2ETest`/`CardStatementSchedulingE2ETest`) verify that re-writing on the same day/same month doesn't change the outcome.
- **A DLQ is required**: `task-queue-dlq.fifo` + `maxReceiveCount=3`, the same convention as `outbox/`'s DLQ.
- **Cron exceptions are logged explicitly**: both Schedulers explicitly record failures via `try`/`catch` + `log.error`, and never rethrow the exception (leaving it to the next tick's retry).

---

## Harness verification

`harness/src/rules/SchedulerInInfrastructureOnly.java` (rule: `scheduler-in-infrastructure-only`) fails the build if it finds `@Scheduled`/`@EnableScheduling` usage in `domain/`·`application/` — since it's a blocklist approach, this repository's actually legitimate uses pass, such as `outbox/OutboxPoller.java`/`taskqueue/TaskOutboxPoller.java` (shared infrastructure packages, not under any domain's `infrastructure/`) and `AccountServiceApplication.java`'s `@EnableScheduling` (the bootstrap entry point). `InterestPaymentScheduler`/`CardStatementScheduler` live in `account/infrastructure/scheduling/`·`card/infrastructure/scheduling/` respectively, so they would have passed under a whitelist approach too — but the blocklist approach reaches the same correct verdict.

`harness/src/rules/NoSilentCatch.java` (rule: `no-silent-catch`) only catches empty catch blocks in `application/`·`infrastructure/` — a Task Controller (`interfaces/task/`) has no catch at all to begin with (it throws exceptions as-is), so it's outside this rule's scope, but by design it can't violate the principle regardless.

---

### Related documents

- [domain-events.md](domain-events.md) — the actual implementation of `OutboxWriter`/`OutboxPoller`/`OutboxConsumer` (the corresponding structure in `taskqueue/`), the Domain Event vs. Task Queue distinction, the 3-level idempotency model
- [layer-architecture.md](layer-architecture.md) — layer placement principles
- [graceful-shutdown.md](graceful-shutdown.md) — `TaskConsumer`'s graceful shutdown via `SmartLifecycle.stop()`
- [shared-modules.md](shared-modules.md) — placement convention for the `taskqueue/` package
