# Domain Event Publishing Pattern

> Framework-agnostic principles: [../../../../docs/architecture/domain-events.md](../../../../docs/architecture/domain-events.md)
>
> **This repository's actual path**: implements as-is the only path the root specifies — **the Outbox load happens inside the `Repository.save()` transaction, queue publishing is done by an independently, periodically running `OutboxPoller`, and EventHandler execution is done by an `OutboxConsumer` that waits to receive from SQS**. There is no mode in this repository where a Command Handler synchronously drains right after saving, in the same process. `src/outbox/outbox_poller.py`/`outbox_consumer.py` are the actual code, and `build_event_handlers()` in `src/outbox/event_handlers.py` is the composition root that assembles the `eventType` → handler dict.

## Event collection — only inside the Aggregate

`src/account/domain/account.py` precisely implements the pattern of collecting events into `_events: list[AccountDomainEvent]` and pulling them out via `pull_events()`.

```python
# src/account/domain/account.py
class Account:
    def __init__(self, ...) -> None:
        ...
        self._events: list[AccountDomainEvent] = []

    def deposit(self, amount: int) -> Transaction:
        ...
        self._events.append(MoneyDeposited(
            account_id=self.account_id,
            transaction_id=transaction.transaction_id,
            email=self.email,
            amount=money,
            balance_after=self.balance,
            created_at=transaction.created_at,
        ))
        return transaction

    def pull_events(self) -> list[AccountDomainEvent]:
        events, self._events = self._events, []
        return events
```

The event types are defined in `src/account/domain/events.py` as `@dataclass(frozen=True)` (`AccountCreated`, `MoneyDeposited`, `MoneyWithdrawn`, `AccountSuspended`, `AccountReactivated`, `AccountClosed`) — following the root principles of past-tense names and immutable objects.

---

## The Outbox pattern — actual implementation

The dual-write structure where a Command Handler directly calls `notification_service.notify()` right after `repo.save()` succeeds is never used — because if the process dies between the save and the notification send, the event could be lost forever. Instead, the Aggregate save and the Outbox load are bundled into a single transaction, and publishing/receiving from Outbox → SQS is left entirely to the independently, periodically running `OutboxPoller`/`OutboxConsumer` — implementing as-is the path the root document specifies: **the Outbox load happens inside the `Repository.save()` transaction, queue publishing is done by an independently running Poller, and EventHandler execution is done by a Consumer that waits to receive from the queue**. There is no mode in this repository where a Command Handler synchronously drains right after saving, in the same process.

### Step 1: the Repository bundles the Aggregate save and the Outbox load into a single transaction

Right after `SqlAlchemyAccountRepository.save()` (`src/account/infrastructure/persistence/account_repository.py`) adds an `AccountModel`/`TransactionModel`, it calls `OutboxWriter` with the same `AsyncSession` to load an Outbox row — everything up until this method returns is the same transaction.

```python
# infrastructure/persistence/account_repository.py — actual code
class SqlAlchemyAccountRepository(AccountRepository):

    def __init__(self, session: AsyncSession) -> None:
        # deferred import — since outbox_model.py imports this module's Base, importing
        # OutboxWriter at the module's top level would create a circular import (see module-pattern.md).
        from ....outbox.outbox_writer import OutboxWriter

        self._session = session
        self._outbox_writer = OutboxWriter(session)

    async def save(self, account: Account) -> None:
        existing = await self._session.get(AccountModel, account.account_id)
        if existing:
            existing.amount = account.balance.amount
            existing.status = account.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(AccountModel(...))

        for transaction in account.pull_pending_transactions():
            self._session.add(TransactionModel(...))

        events = account.pull_events()
        if events:
            await self._outbox_writer.save_all(events)   # Outbox load — same session, same transaction

        await self._session.flush()
```

`OutboxWriter` (`src/outbox/outbox_writer.py`) serializes each event via `dataclasses.asdict()` + `json.dumps(..., default=str)` and adds it as an `OutboxModel` row. A nested dataclass such as `Money` is also recursively flattened by `asdict()`, and a `datetime` is converted by `default=str` into `str(dt)` (a space-separated ISO format).

```python
# src/outbox/outbox_writer.py — actual code
class OutboxWriter:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_all(self, events: Sequence[object]) -> None:
        for event in events:
            # An Integration Event uses its versioned public contract name (event_name,
            # e.g. 'account.suspended.v1') as event_type. A Domain Event has no event_name,
            # so its class name is used as-is.
            event_type = getattr(event, "event_name", None) or type(event).__name__
            self._session.add(OutboxModel(
                event_id=uuid.uuid4().hex,
                event_type=event_type,
                payload=json.dumps(dataclasses.asdict(event), default=str),
                processed=False,
            ))
```

### Outbox table schema

```python
# src/outbox/outbox_model.py — actual code
class OutboxModel(Base):
    __tablename__ = "outbox"

    event_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    event_type: Mapped[str]                 # e.g. "MoneyDeposited"
    payload: Mapped[str]                    # JSON-serialized
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

`Base` is imported and reused as-is from where it's defined in `src/account/infrastructure/persistence/account_repository.py` — in production, an Alembic migration creates the `outbox` table, and the local/test-only `Base.metadata.create_all` (each e2e test's testcontainers fixture) creates the same table too (see `persistence.md` — `main.py`'s `lifespan` never calls `create_all`).

### Step 2: `OutboxPoller` — sending Outbox → SQS (actual code)

`src/outbox/outbox_poller.py` — a background loop that `main.py`'s `lifespan` starts exactly once at app startup via `asyncio.create_task()`. A Command Handler never references this class at all — if it did, the harness's `outbox-no-sync-drain` rule would catch it.

```python
# application/command/deposit_handler.py — actual code
class DepositHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DepositCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)
        await self._repo.save(account)  # Aggregate save + Outbox load (one transaction)
        return transaction  # ends here — publishing/receiving Outbox → SQS is handled independently by OutboxPoller/OutboxConsumer
```

`OutboxPoller.run_forever()` reads up to 100 rows with `processed=False` every 1 second, publishes them to SQS, and immediately marks a successfully published row as `processed=True` — **the meaning of `processed` shifts from "the handler finished processing it" to "delivery to SQS is complete."** From this point on, retry/at-least-once guarantees are the responsibility of SQS's visibility timeout + DLQ, not the outbox table.

```python
# src/outbox/outbox_poller.py — actual code (excerpt)
class OutboxPoller:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        while True:
            try:
                await self._drain_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Outbox polling failed")
            await asyncio.sleep(POLL_INTERVAL_SECONDS)  # 1 second

    async def _drain_once(self) -> None:
        queue_url = SqsConfig().domain_event_queue_url
        async with self._session_factory() as session:
            stmt = (
                select(OutboxModel).where(OutboxModel.processed.is_(False))
                .order_by(OutboxModel.created_at).limit(BATCH_SIZE)
            )
            rows = (await session.execute(stmt)).scalars().all()
            if not rows:
                return

            async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:
                for row in rows:
                    try:
                        # Carries this Outbox row's event_id in the payload — the handler
                        # can use it as a Ledger key to skip duplicate processing caused by
                        # a retry on its own (see "Event Handler Idempotency").
                        body = json.loads(row.payload)
                        body["outbox_event_id"] = row.event_id
                        await sqs_client.send_message(
                            QueueUrl=queue_url,
                            MessageBody=json.dumps(body),
                            MessageAttributes={"eventType": {"DataType": "String", "StringValue": row.event_type}},
                        )
                        row.processed = True
                    except Exception:
                        logger.exception("Failed to publish to SQS: event_type=%s event_id=%s", row.event_type, row.event_id)

            await session.commit()
```

`outbox_event_id` is embedded into the payload by `OutboxPoller` **at publish time**. `OutboxConsumer` passes this value straight through to the handler, so each EventHandler's code only needs to read `payload["outbox_event_id"]`.

### Step 3: `OutboxConsumer` — receiving SQS → EventHandler (actual code)

`src/outbox/outbox_consumer.py` — likewise started exactly once at app startup by `main.py`'s `lifespan`. It long-polls via `receive_message`'s `WaitTimeSeconds`, and once it receives a message, looks up the handler in the dict `build_event_handlers()` assembled by `eventType` (MessageAttributes) and calls it.

```python
# src/outbox/outbox_consumer.py — actual code (excerpt)
class OutboxConsumer:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        queue_url = SqsConfig().domain_event_queue_url
        async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:
            while True:
                result = await sqs_client.receive_message(
                    QueueUrl=queue_url, MaxNumberOfMessages=10,
                    MessageAttributeNames=["eventType"], WaitTimeSeconds=5,
                )
                for message in result.get("Messages", []):
                    await self._handle_message(sqs_client, queue_url, message)

    async def _handle_message(self, sqs_client, queue_url: str, message: dict) -> None:
        event_type = message.get("MessageAttributes", {}).get("eventType", {}).get("StringValue")
        try:
            if not event_type:
                raise ValueError("The eventType message attribute is missing.")
            payload = json.loads(message.get("Body", "{}"))
            async with self._session_factory() as session:
                handler = build_event_handlers(session).get(event_type)
                if handler is None:
                    raise ValueError(f"No registered handler: {event_type}")
                await handler(payload)
                await session.commit()
            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except Exception:
            logger.exception("Failed to process event: event_type=%s", event_type)
            # Not deleted — it will be re-received and retried after the visibility timeout.
```

Handler success → deleted (ack) via `delete_message`. Handler failure (or no registered handler) → not deleted → SQS automatically redelivers it after the visibility timeout (at-least-once) — the EventHandler idempotency this repository requires is premised exactly on this redelivery.

**A new `AsyncSession` is opened per message** — `_handle_message()` opens a fresh session each time via `async with self._session_factory() as session:` and calls `build_event_handlers(session)` right there to assemble the Repository/Service instances. The reason a session isn't created once at Consumer construction and reused continuously is the "one session per unit of work" principle from `persistence.md` — sharing a single session across multiple messages would blur the transaction boundary and risk a failed message's changes leaking into the next message.

### `build_event_handlers()` — assembling handler routing (actual code)

`build_event_handlers(session) -> dict[str, Callable]` in `src/outbox/event_handlers.py` assembles the entire wiring from an `eventType` string → its processing function. Since FastAPI has no loose inter-module registration mechanism like NestJS's `EventHandlerRegistry`, this single function is the composition root for all three BCs — Account/Card/Payment — (`OutboxConsumer` calls this function with a new session every time it processes a message; "assembled once" means the *definition* of this assembly logic is fixed, not that a single session is held for the entire life of the process).

```python
# src/outbox/event_handlers.py — actual code (excerpt)
def build_event_handlers(session: AsyncSession) -> dict[str, EventHandlerFn]:
    account_repo = SqlAlchemyAccountRepository(session)
    card_repo = SqlAlchemyCardRepository(session)
    notification_service = SesNotificationService(session)
    outbox_writer = OutboxWriter(session)
    card_integration_event_controller = CardIntegrationEventController(card_repo)
    account_integration_event_controller = AccountIntegrationEventController(account_repo)

    return {
        "AccountCreated": AccountCreatedEventHandler(notification_service).handle,
        "MoneyDeposited": MoneyDepositedEventHandler(notification_service).handle,
        "MoneyWithdrawn": MoneyWithdrawnEventHandler(notification_service).handle,
        "AccountSuspended": AccountSuspendedEventHandler(notification_service, outbox_writer).handle,
        "AccountReactivated": AccountReactivatedEventHandler(notification_service).handle,
        "AccountClosed": AccountClosedEventHandler(notification_service, outbox_writer).handle,
        "account.suspended.v1": card_integration_event_controller.on_account_suspended,
        "account.closed.v1": card_integration_event_controller.on_account_closed,
        "PaymentCompleted": PaymentCompletedEventHandler(outbox_writer).handle,
        "PaymentCancelled": PaymentCancelledEventHandler(outbox_writer).handle,
        "RefundApproved": RefundApprovedEventHandler(outbox_writer).handle,
        "payment.completed.v1": account_integration_event_controller.on_payment_completed,
        "payment.cancelled.v1": account_integration_event_controller.on_payment_cancelled,
        "refund.approved.v1": account_integration_event_controller.on_refund_approved,
    }
```

This assembly is fixed as a single function in `src/outbox/`, and `OutboxConsumer` reuses it per message — it isn't tied to an HTTP request scope. The EventHandler code in `application/event/` (`account_created_event_handler.py`, etc.) only needs a signature that takes a payload (dict) and processes it, so it's reused as-is regardless of this assembly approach.

`SesNotificationService.notify()` (`src/account/infrastructure/notification/notification_service.py`) catches an SES send failure, only logs it, and swallows it — this failure is also caught by `OutboxConsumer._handle_message()`'s `try/except`, so the result is the same regardless of which layer catches it: the message isn't deleted, so SQS redelivers it. `notify()` first checks, before actually sending, whether the event has already been processed based on `outbox_event_id` (Ledger idempotency, see below).

---

## Event handler idempotency

SQS guarantees **at-least-once** delivery, so the same message can be received more than once. To prevent this, the EventHandler that sends a notification applies Level 2 (Ledger) idempotency — `SentEmailModel` (`src/account/infrastructure/notification/sent_email_model.py`) doubles as both the send-history Entity and the Ledger.

The Ledger key is the Outbox row's `event_id` (not `account_id`+`event_type` — since the same account can have the same kind of event happen multiple times, that combination alone can't pin down "this one specific event"). `OutboxPoller` carries `outbox_event_id` (= `row.event_id`) in the payload at the moment it publishes to SQS, `OutboxConsumer` passes it straight through to the handler, and each EventHandler passes it straight through to `NotificationService.notify(event, outbox_event_id)`.

```python
# infrastructure/notification/sent_email_model.py — actual code (excerpt)
class SentEmailModel(Base):
    __tablename__ = "sent_emails"

    sent_email_id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str]
    event_type: Mapped[str]
    ...
    outbox_event_id: Mapped[str | None] = mapped_column(nullable=True, index=True, default=None)
    sent_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

```python
# infrastructure/notification/notification_service.py — actual code (excerpt)
async def _send_and_record(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
    event_type = type(event).__name__

    already_sent = (
        await self._session.execute(
            select(SentEmailModel).where(SentEmailModel.outbox_event_id == outbox_event_id)
        )
    ).scalar_one_or_none()
    if already_sent is not None:
        logger.info("Event already sent — skipping duplicate send", extra={...})
        return  # already sent — skip the duplicate

    subject, body = _render(event)
    ...
    self._session.add(SentEmailModel(..., outbox_event_id=outbox_event_id))
    await self._session.flush()
```

The check (lookup) and record (save) both happen within the single `notify()` call, using the `AsyncSession` that `OutboxConsumer` freshly opened to process this message (an entirely separate transaction from the Aggregate save transaction — the two now run at different points in time, on different process ticks). The Card BC's reaction handlers (`SuspendCardsByAccountHandler`/`CancelCardsByAccountHandler`) only pick and process ACTIVE (or ACTIVE·SUSPENDED) cards — Level 1 (intrinsic idempotency) — so they need no Ledger; only the Account-side EventHandler that sends notifications needed Level 2.

For the full summary of idempotency principles, see the root's [domain-events.md#event-handler-idempotency](../../../../docs/architecture/domain-events.md#event-handler-idempotency).

---

## Domain Event vs. Integration Event

With `account` and `card`, once a second Bounded Context existed, Integration Events actually came into use. An internal Domain Event such as `AccountSuspended`/`AccountClosed` is never exposed externally as-is — it's converted in `application/integration_event/` into a separate, versioned contract (`account.suspended.v1`, `account.closed.v1`) before being published — see the root's [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md). The concrete flow is laid out in [cross-domain.md](cross-domain.md).

```python
# src/account/application/integration_event/account_suspended_integration_event.py — actual code
@dataclass(frozen=True)
class AccountSuspendedIntegrationEventV1:
    event_name: ClassVar[str] = "account.suspended.v1"  # not included in the asdict() payload
    account_id: str
    suspended_at: str
```

The conversion point is always the EventHandler in `application/event/` (an Aggregate never creates an Integration Event directly). `application/event/` is the sole exception allowed to use `OutboxWriter` directly, loading the Integration Event it creates here into the Outbox in the same session.

```python
# src/account/application/event/account_suspended_event_handler.py — actual code (excerpt)
class AccountSuspendedEventHandler:
    def __init__(self, notification_service: NotificationService, outbox_writer: OutboxWriter) -> None:
        self._notification_service = notification_service
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        event = AccountSuspended(...)
        await self._outbox_writer.save_all([
            AccountSuspendedIntegrationEventV1(account_id=event.account_id, suspended_at=event.suspended_at.isoformat())
        ])
        await self._notification_service.notify(event, payload["outbox_event_id"])
```

On the receiving side (the Card BC), `interface/integration_event/card_integration_event_controller.py` handles it — an input boundary at the same location (`interface/`) as an HTTP Router, calling only its own domain's Command Handler. The assembly is handled by `build_event_handlers()` in `src/outbox/event_handlers.py` — registering Account's own 6 Domain Event handlers and Card's two Integration Event reaction handlers into the same `handlers` dict (FastAPI has no loose inter-module registration mechanism like NestJS's `EventHandlerRegistry`).

Thanks to this wiring, a `POST /accounts/{id}/suspend` request completes in the following order — it isn't completed within a single HTTP request/transaction, but progresses asynchronously across several stages:
1. Within the HTTP request, the `AccountSuspended` Domain Event is loaded into the Outbox and the response is returned.
2. The next `OutboxPoller` tick (up to 1 second later) publishes this row to SQS.
3. `OutboxConsumer` receives it and calls `AccountSuspendedEventHandler` — sending the notification and additionally loading the `account.suspended.v1` Integration Event into the Outbox of the same session (freshly opened to process that message).
4. The next `OutboxPoller` tick also publishes this Integration Event to SQS.
5. `OutboxConsumer` receives it and calls the Card BC's `SuspendCardsByAccountHandler` — turning every linked ACTIVE card to SUSPENDED.

The E2E test (`test_card_e2e.py`) assumes this async round trip and confirms the final state with the `wait_until()` polling helper (see `testing.md`) — asserting immediately right after the response fails.

---

## Principles

- **Event collection happens only inside the Aggregate**: implemented via `_events` + `pull_events()`.
- **Saving and event loading are a single transaction**: `SqlAlchemyAccountRepository.save()` loads the Aggregate state and the Outbox row together, and the request-scoped session commits them at once.
- **A Command Handler returns immediately after saving**: it never calls `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` directly — the harness catches synchronous draining via the `outbox-no-sync-drain` rule.
- **Delivery order is Outbox → SQS → Handler**: `OutboxPoller` (publish) and `OutboxConsumer` (receive/execute) each run independently and periodically. It's an `asyncio.create_task()` background loop started by `main.py`'s `lifespan`, not a polling worker (APScheduler/Celery).
- **Handlers are implemented to be idempotent**: prepared for SQS's at-least-once delivery characteristic. The Account-side EventHandler that sends notifications applies Ledger-based (`SentEmailModel.outbox_event_id`) duplicate-skipping (Level 2). The Card BC's reaction handlers (`SuspendCardsByAccountHandler`/`CancelCardsByAccountHandler`) only pick and process ACTIVE (or ACTIVE·SUSPENDED) cards, so they're naturally idempotent on redelivery (Level 1).
- **Handler routing is fixed in `src/outbox/event_handlers.py`**: `build_event_handlers()` is the composition root for all three BCs — Account/Card/Payment — it isn't reassembled per request by a per-domain router.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Domain Event definitions (`events.py`)
- [repository-pattern.md](repository-pattern.md) — saving to the Outbox in the Repository
- [shared-modules.md](shared-modules.md) — the location of the shared `src/outbox/` package
- [persistence.md](persistence.md) — the transaction boundary
