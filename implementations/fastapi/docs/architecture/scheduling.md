# Scheduling / Task Queue Implementation Pattern

> Framework-agnostic principles: [../../../../docs/architecture/scheduling.md](../../../../docs/architecture/scheduling.md)
>
> **This repository's actual path**: implements as-is the path the root specifies, `[Scheduler] --(enqueue)--> [task_outbox] --(Poller)--> [SQS FIFO] --(Consumer)--> [TaskController] --(calls)--> [CommandService]`. The Scheduler (APScheduler's `@scheduled_job`) does nothing but insert a `task_outbox` row — the actual business logic, such as interest calculation or statistics aggregation, is the responsibility of the `TaskController` (→ Command Handler) that `TaskConsumer` receives from SQS and calls. `src/task_queue/` is the actual code, made up of `task_outbox_writer.py`/`task_outbox_poller.py`/`task_consumer.py`/`task_handlers.py`. The overall structure described here is **one implementation example** this repository actually adopted — a simpler batch job might be well served by just `BackgroundTasks` or a single `@scheduled_job` + a direct Handler call (see "Options" below).

Two periodic jobs (regular account interest payment, monthly card statement delivery) are implemented with this pattern — `src/account/infrastructure/scheduling/interest_scheduler.py` and `src/card/infrastructure/scheduling/statement_scheduler.py`.

---

## Options — `BackgroundTasks` vs. APScheduler alone vs. APScheduler + Task Queue

| | FastAPI `BackgroundTasks` | APScheduler alone (calling the Handler directly) | APScheduler + Task Outbox + SQS FIFO (adopted by this repository) |
|---|---|---|---|
| When it runs | Right after the response is returned, same process | Cron/interval, same process | Cron only enqueues; execution happens in a separate Consumer |
| Multi-instance safety | None | None (duplicate execution without a separate lock) | Yes (the FIFO queue's `MessageDeduplicationId`) |
| Retry on failure | None | None (by default) | Yes (SQS visibility timeout + `maxReceiveCount` → DLQ) |
| Suited for | Short post-processing right after the response, fine if it fails | Single-instance deployment, lightweight periodic jobs assuming idempotent handling | Batches across multiple instances that need reliable retries/DLQ visibility |
| Dependency added to this repository | None (built into FastAPI) | `apscheduler` | `apscheduler` + an SQS FIFO queue (separate from domain events) |

Both interest payment and statement delivery are batch jobs that need "exactly once per day/month even with multiple instances running concurrently" plus "isolate failures into a DLQ," so the third column was chosen. A batch that iterates the entire set of Aggregates page by page, like `apply_daily_interest_handler.py`/`send_monthly_card_statement_handler.py`, can take longer as it grows, but this is a scale that can be handled just by setting SQS FIFO's visibility timeout generously — beyond that (hundreds of thousands of records, needing a distributed lock), consider switching to Celery as discussed in the root document's "Options" section.

---

## The Scheduler — the Infrastructure layer, enqueue only

`src/account/infrastructure/scheduling/interest_scheduler.py` and `src/card/infrastructure/scheduling/statement_scheduler.py` are factory functions wrapping APScheduler's `AsyncIOScheduler`. The Cron handler body is nothing but a call to `TaskOutboxWriter.enqueue()` and `session.commit()` — there is no actual logic like interest calculation or looking up cards at all.

```python
# src/account/infrastructure/scheduling/interest_scheduler.py — actual code
TASK_TYPE = "account.interest.apply"
GROUP_ID = "account.interest"

def start_interest_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", hour=0, minute=0, id="apply-daily-interest")
    async def _run() -> None:
        try:
            today = date.today()
            async with session_factory() as session:
                await TaskOutboxWriter(session).enqueue(
                    task_type=TASK_TYPE,
                    payload={},
                    group_id=GROUP_ID,
                    deduplication_id=f"{TASK_TYPE}-{today.isoformat()}",
                )
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.exception("Failed to enqueue the regular interest-payment Task")
            # Not re-raised — it will be retried on the next tick (the next day).

    scheduler.start()
    return scheduler
```

`main.py`'s `lifespan` calls `start_interest_scheduler(SessionLocal)`/`start_statement_scheduler(SessionLocal)` at app startup to start each scheduler, and on shutdown calls `scheduler.shutdown(wait=True)` to wait for in-progress jobs to finish before shutting down (see graceful-shutdown.md). If such a scheduling decorator ever appears directly in `domain/`/`application/`, the harness's `scheduler-in-infrastructure-only` rule catches it — `src/outbox/`/`src/task_queue/` (like the `asyncio.create_task()` loop in `src/outbox/outbox_poller.py`) are already shared-infrastructure locations ([shared-modules.md](shared-modules.md)), so they fall outside the scope of this rule.

---

## The Task Outbox pattern — actual implementation

A Cron tick has no natural DB transaction, so `TaskOutboxWriter.enqueue()`'s single row insert itself provides atomicity. The structure is identical to Domain Event's `OutboxModel`/`OutboxWriter` (`src/outbox/`, see [domain-events.md](domain-events.md)), differing only in that the table and queue are separate — "load within the same transaction as the write → a separate Poller publishes it."

```python
# src/task_queue/task_outbox_model.py — actual code
class TaskOutboxModel(Base):
    __tablename__ = "task_outbox"

    task_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    task_type: Mapped[str]
    payload: Mapped[str]
    group_id: Mapped[str]            # FIFO MessageGroupId
    deduplication_id: Mapped[str]    # FIFO MessageDeduplicationId (date/month-based)
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

```python
# src/task_queue/task_outbox_writer.py — actual code
class TaskOutboxWriter:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def enqueue(self, task_type: str, payload: dict, group_id: str, deduplication_id: str) -> None:
        self._session.add(TaskOutboxModel(
            task_id=uuid.uuid4().hex,
            task_type=task_type,
            payload=json.dumps(payload, default=str),
            group_id=group_id,
            deduplication_id=deduplication_id,
            processed=False,
        ))
```

`TaskOutboxPoller` (`src/task_queue/task_outbox_poller.py`) operates with exactly the same separation of responsibilities as `OutboxPoller` — every 1 second it reads up to 100 rows with `processed=False`, publishes them to `SqsConfig().task_queue_url` (a FIFO queue), and sends `MessageGroupId`/`MessageDeduplicationId` along with them. Only a row that was published successfully is marked `processed=True`. `main.py`'s `lifespan` starts `TaskOutboxPoller(SessionLocal)` once at app startup via `asyncio.create_task()` — the Scheduler (the Cron handler) never references this class.

---

## The Task Controller — the Interface layer

`src/account/interface/task/account_task_controller.py` and `src/card/interface/task/card_task_controller.py` are Task Controllers. Just as an HTTP Router delegates an HTTP request to a Command Handler, this only delegates, with no logic, and lets exceptions propagate as-is.

```python
# src/account/interface/task/account_task_controller.py — actual code
class AccountTaskController:
    def __init__(self, handler: ApplyDailyInterestHandler) -> None:
        self._handler = handler

    async def apply_daily_interest(self, payload: dict) -> None:
        del payload  # this Task has no payload — it runs based on the current date
        await self._handler.execute(InterestConfig().daily_rate)
```

`TaskConsumer` (`src/task_queue/task_consumer.py`) long-polls `SqsConfig().task_queue_url` with the same structure as `OutboxConsumer`, and once it receives a message, looks up the Task Controller method in the dict assembled by `build_task_handlers()` (`src/task_queue/task_handlers.py`) by `taskType` (MessageAttributes) and calls it.

```python
# src/task_queue/task_handlers.py — actual code (excerpt)
def build_task_handlers(session: AsyncSession) -> dict[str, TaskHandlerFn]:
    account_task_controller = AccountTaskController(ApplyDailyInterestHandler(account_repo))
    card_task_controller = CardTaskController(
        SendMonthlyCardStatementHandler(card_repo, account_adapter, payment_adapter)
    )
    return {
        "account.interest.apply": account_task_controller.apply_daily_interest,
        "card.statement.send": card_task_controller.send_monthly_statement,
    }
```

Unlike Domain Event's `build_event_handlers()` (a 1:N fan-out, with a `list[Callable]` value type), a Task has exactly one handler per `taskType`, so its value type is a single `Callable` (see "Task Queue vs Domain Event" below). Handler success → `delete_message` (ack). Failure (or no registered handler) → not deleted — once the FIFO queue's visibility timeout passes, it's automatically redelivered, and once `maxReceiveCount` is exceeded, it's isolated into the DLQ.

---

## Task Queue vs. Domain Event — this repository's actual distinction

| | Domain Event (`src/outbox/`) | Task Queue (`src/task_queue/`) |
|---|---|---|
| Unit of meaning | A fact: `InterestPaid`, `CardStatementSent` | A command: `account.interest.apply`, `card.statement.send` |
| Producer | An Aggregate (`Account.apply_interest()`, `Card.send_statement()`) | The Scheduler (APScheduler's `@scheduled_job`) |
| Number of handlers | 1:N (`build_event_handlers()`'s value fans out to multiple keys, not a list) | 1:1 (`build_task_handlers()`, exactly one per taskType) |
| Queue | The `domain-events` standard queue | `tasks.fifo` — a physically separate FIFO queue (+ `tasks-dlq.fifo`) |
| Example in this repository | `Account.apply_interest()` → `InterestPaid` → a notification email | `interest_scheduler` enqueues an `account.interest.apply` Task every midnight |

The regular interest-payment batch chains the Task Queue and Domain Event together in one flow: **APScheduler (Cron) enqueues a Task → `TaskConsumer` receives it and calls `AccountTaskController.apply_daily_interest()` → `ApplyDailyInterestHandler` iterates ACTIVE accounts calling `Account.apply_interest()` → an `InterestPaid` Domain Event is loaded into the Outbox for each account that received interest → `OutboxPoller`/`OutboxConsumer` publish/receive it again, and `InterestPaidEventHandler` sends the SES notification.** The card-statement delivery has the same dual structure (`card.statement.send` Task → `SendMonthlyCardStatementHandler` → `Card.send_statement()` → `CardStatementSent` Domain Event → SES notification). "What triggers the batch" and "what announces the fact the batch produced" are clearly separated into different queues and different units of meaning.

---

## The `MessageGroupId` strategy — this repository's actual values

Both of this repository's batches are global Cron batches, so `taskType` itself is used as the group (same as the root document's "global Cron batch" row).

| Task | `group_id` | `deduplication_id` |
|------|-----------|---------------------|
| `account.interest.apply` | `"account.interest"` | `f"account.interest.apply-{today.isoformat()}"` (per-date) |
| `card.statement.send` | `"card.statement"` | `f"card.statement.send-{YYYY-MM}"` (per-month) |

Even if multiple instances tick concurrently on the same day (or month), only 1 item enters the queue within the FIFO queue's 5-minute dedup window, since `deduplication_id` is identical.

---

## Idempotency — Level 1, implemented via an Aggregate field

Both batches use **Level 1 (intrinsic idempotency)**, deciding "has today/this month already been processed" via the Aggregate's own field, with no separate Ledger table — the same model as [domain-events.md#event-handler-idempotency](domain-events.md#event-handler-idempotency).

```python
# src/account/domain/account.py — actual code (excerpt)
def apply_interest(self, daily_rate: Decimal, today: date) -> Transaction | None:
    if self.status != AccountStatus.ACTIVE:
        return None
    if self.last_interest_paid_at == today:
        return None  # already processed today — a complete no-op

    self.last_interest_paid_at = today
    interest_amount = int((Decimal(self.balance.amount) * daily_rate).to_integral_value(rounding=ROUND_FLOOR))
    if interest_amount <= 0:
        return None  # payment is skipped, but the fact "today was processed" is already recorded

    ...
    self._events.append(InterestPaid(...))
    return transaction
```

```python
# src/card/domain/card.py — actual code (excerpt)
def send_statement(self, period: str, payment_count: int, total_amount: int, email: str) -> None:
    if self.last_statement_sent_month == period:
        return  # already sent this month — a complete no-op
    self.last_statement_sent_month = period
    self._events.append(CardStatementSent(...))
```

`last_interest_paid_at` (`Account`) and `last_statement_sent_month` (`Card`) are nullable columns added to the `accounts`/`cards` tables by the migration `8f1c2a4e6d90_add_scheduling_task_queue_tables.py`, and the Repository's `save()` saves them together every time. Even if a Task is redelivered by SQS's at-least-once semantics and `ApplyDailyInterestHandler`/`SendMonthlyCardStatementHandler` run the same batch twice, the second run returns an immediate no-op on every Aggregate, so interest/emails are never duplicated — `test_scheduling_e2e.py`'s redelivery-simulation test verifies this directly.

The card-statement batch has one more layer of idempotency on the Domain Event side too — the Card-specific `SesNotificationService` that `CardStatementSentEventHandler` calls uses Level 2 (a Ledger, `SentStatementEmailModel.outbox_event_id`) to prevent duplicate SES sends, the same as on the Account side. The Aggregate field (Level 1) is responsible for "when to prevent reprocessing," and the Ledger (Level 2) for "whether to prevent duplication of the SES send itself" — a dual defense.

---

## Payload size — both of this repository's Tasks have an empty payload

Both `account.interest.apply`/`card.statement.send` are enqueued with `payload={}` — the execution moment itself, "today"/"this month," is the only input, and this is computed directly by the Command Handler (not the `TaskController`) via `date.today()`. This is an extreme case of the root document's "only small metadata" principle applied. If a Task targeting a specific Aggregate ID (e.g. reprocessing only a specific account) is ever needed, add a minimal payload at the level of `{ "account_id": "..." }`.

---

## The DLQ — local development environment setup

`localstack/init-sqs.sh` creates `tasks-dlq.fifo` → `tasks.fifo` (RedrivePolicy `maxReceiveCount: 3`) in that order — reproducing the same setup as the Domain Event queue (`domain-events`/`domain-events-dlq`) as a FIFO. `SQS_TASK_QUEUE_URL` in `docker-compose.yml`/`.env.example` is the queue URL the application connects to, and `SqsConfig.task_queue_url` in `src/config/sqs_config.py` validates it as a required fail-fast value.

---

## Principles

- **The Scheduler is in the Infrastructure layer, enqueue only**: `interest_scheduler.py`/`statement_scheduler.py` do nothing beyond calling `TaskOutboxWriter.enqueue()`. If a scheduling decorator ever appears in `domain/`/`application/`, the harness's `scheduler-in-infrastructure-only` rule catches it.
- **Loading goes through the Task Outbox**: the single row insert of a Cron tick itself provides atomicity. `TaskOutboxPoller` independently publishes it to SQS FIFO.
- **The Task Controller is in the Interface layer**: `account_task_controller.py`/`card_task_controller.py` delegate to the Command Handler with no logic and let exceptions propagate as-is — `TaskConsumer` decides on retry/DLQ.
- **Handler routing is fixed in `src/task_queue/task_handlers.py`**: `build_task_handlers()` is the composition root that assembles one handler per taskType (a structure symmetric to Domain Event's `build_event_handlers()`, but with a single Callable value type instead of a list).
- **Idempotency is implemented via an Aggregate field (Level 1)**: `Account.last_interest_paid_at`/`Card.last_statement_sent_month` — a complete no-op even on redelivery.
- **The Task queue is a physically separate FIFO queue from the Domain Event queue**: `tasks.fifo`/`tasks-dlq.fifo` — with the infrastructure difference of using `MessageGroupId`/`MessageDeduplicationId`.
- **Cron exceptions are logged explicitly**: both Schedulers wrap the body in `try/except` + `logger.exception()`, without re-raising (retried on the next tick).

---

### Related documents

- [domain-events.md](domain-events.md) — the actual implementation of `OutboxPoller`/`OutboxConsumer`, the second async path (the `InterestPaid`/`CardStatementSent` Domain Events) paired with the Task Queue
- [layer-architecture.md](layer-architecture.md) — layer responsibilities, `Depends`-based DI
- [graceful-shutdown.md](graceful-shutdown.md) — `scheduler.shutdown(wait=True)`/the Consumer's safe shutdown
- [persistence.md](persistence.md) — the transaction boundary of the batch job, the `last_interest_paid_at`/`last_statement_sent_month` columns
