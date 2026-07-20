# 도메인 이벤트 발행 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/domain-events.md](../../../../docs/architecture/domain-events.md)

> **이 저장소의 실제 경로**: root가 규정하는 유일한 경로 — **Outbox 적재는 Repository.save() 트랜잭션 안에서, 큐 발행은 독립적으로 주기 실행되는 `OutboxPoller`가, EventHandler 실행은 SQS를 수신 대기하는 `OutboxConsumer`가** — 를 그대로 구현한다. Command Handler가 저장 직후 같은 프로세스 안에서 동기적으로 드레인하는 방식은 이 저장소에 없다. `src/outbox/outbox_poller.py`/`outbox_consumer.py`가 실제 코드이며, `src/outbox/event_handlers.py`의 `build_event_handlers()`가 `eventType` → 핸들러 dict를 조립하는 composition root다.

## 이벤트 수집 — Aggregate 안에서만

`src/account/domain/account.py`는 `_events: list[AccountDomainEvent]`에 이벤트를 모으고 `pull_events()`로 꺼내는 패턴을 정확히 구현하고 있다.

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

이벤트 타입은 `src/account/domain/events.py`에 `@dataclass(frozen=True)`로 정의되어 있다(`AccountCreated`, `MoneyDeposited`, `MoneyWithdrawn`, `AccountSuspended`, `AccountReactivated`, `AccountClosed`) — 과거형 이름, 불변 객체라는 root 원칙을 따른다.

---

## Outbox 패턴 — 실제 구현

이전에는 `repo.save()`가 성공한 직후 커맨드 핸들러가 `notification_service.notify()`를 직접 호출하는 dual-write 구조였다 — 저장과 알림 발송 사이에 프로세스가 죽으면 이벤트가 영원히 유실될 수 있었다. 지금은 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로 묶고, Outbox → SQS 발행/수신을 독립적으로 주기 실행되는 `OutboxPoller`/`OutboxConsumer`에게 완전히 맡긴다 — root 문서가 규정하는 **Outbox 적재는 Repository.save() 트랜잭션 안에서, 큐 발행은 독립적으로 주기 실행되는 Poller가, EventHandler 실행은 큐를 수신 대기하는 Consumer가** 담당하는 경로를 그대로 구현한다. Command Handler가 저장 직후 같은 프로세스 안에서 동기적으로 드레인하는 방식(2026-07 이전 방식)은 이 저장소에 더 이상 없다.

### 1단계: Repository가 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로 묶는다

`SqlAlchemyAccountRepository.save()`(`src/account/infrastructure/persistence/account_repository.py`)는 `AccountModel`/`TransactionModel`을 추가한 직후, 같은 `AsyncSession`으로 `OutboxWriter`를 호출해 Outbox row를 적재한다 — 이 메서드가 반환할 때까지는 모두 같은 트랜잭션이다.

```python
# infrastructure/persistence/account_repository.py — 실제 코드
class SqlAlchemyAccountRepository(AccountRepository):

    def __init__(self, session: AsyncSession) -> None:
        # 지연 import — outbox_model.py가 이 모듈의 Base를 import하므로, 모듈 최상단에서
        # OutboxWriter를 import하면 순환 참조가 발생한다 (module-pattern.md 참조).
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
            await self._outbox_writer.save_all(events)   # Outbox 적재 — 같은 세션, 같은 트랜잭션

        await self._session.flush()
```

`OutboxWriter`(`src/outbox/outbox_writer.py`)는 각 이벤트를 `dataclasses.asdict()` + `json.dumps(..., default=str)`로 직렬화해 `OutboxModel` row로 추가한다. `Money` 같은 중첩 dataclass도 `asdict()`가 재귀적으로 평탄화하고, `datetime`은 `default=str`이 `str(dt)`(공백 구분 ISO 형식)로 변환한다.

```python
# src/outbox/outbox_writer.py — 실제 코드
class OutboxWriter:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_all(self, events: Sequence[object]) -> None:
        for event in events:
            # Integration Event는 버전이 명시된 공개 계약명(event_name, 예: 'account.suspended.v1')을
            # event_type으로 쓴다. Domain Event는 event_name이 없으므로 클래스명을 그대로 쓴다.
            event_type = getattr(event, "event_name", None) or type(event).__name__
            self._session.add(OutboxModel(
                event_id=uuid.uuid4().hex,
                event_type=event_type,
                payload=json.dumps(dataclasses.asdict(event), default=str),
                processed=False,
            ))
```

### Outbox 테이블 스키마

```python
# src/outbox/outbox_model.py — 실제 코드
class OutboxModel(Base):
    __tablename__ = "outbox"

    event_id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
    event_type: Mapped[str]                 # 예: "MoneyDeposited"
    payload: Mapped[str]                    # JSON 직렬화
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

`Base`는 `src/account/infrastructure/persistence/account_repository.py`에 정의된 것을 그대로 import해서 재사용한다 — 프로덕션에서는 Alembic 마이그레이션이 `outbox` 테이블을 만들고, 로컬/테스트 전용 `Base.metadata.create_all`(각 e2e 테스트의 testcontainers 픽스처)도 같은 테이블을 만든다(`persistence.md` 참고 — `main.py`의 `lifespan`은 `create_all`을 호출하지 않는다).

### 2단계: `OutboxPoller` — Outbox → SQS 전송 (실제 코드)

`src/outbox/outbox_poller.py` — `main.py`의 `lifespan`이 앱 기동 시 `asyncio.create_task()`로 단 한 번 띄우는 백그라운드 루프다. Command Handler는 이 클래스를 전혀 참조하지 않는다 — 참조하면 harness의 `outbox-no-sync-drain` 규칙이 잡아낸다.

```python
# application/command/deposit_handler.py — 실제 코드
class DepositHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DepositCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)
        await self._repo.save(account)  # Aggregate 저장 + Outbox 적재 (하나의 트랜잭션)
        return transaction  # 여기서 끝난다 — Outbox → SQS 발행/수신은 OutboxPoller/OutboxConsumer가 독립적으로 처리한다
```

`OutboxPoller.run_forever()`는 1초 주기로 `processed=False` 행을 최대 100개씩 읽어 SQS로 발행하고, 발행에 성공한 행을 즉시 `processed=True`로 표시한다 — **`processed`의 의미가 "핸들러가 처리를 끝냈다"에서 "SQS로 전달을 끝냈다"로 바뀐다.** 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility timeout + DLQ가 담당한다.

```python
# src/outbox/outbox_poller.py — 실제 코드(발췌)
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
                logger.exception("Outbox 폴링 실패")
            await asyncio.sleep(POLL_INTERVAL_SECONDS)  # 1초

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
                        # 이 Outbox 행의 event_id를 payload에 실어 보낸다 — 핸들러가 Ledger 키로
                        # 써서 재시도로 인한 중복 처리를 스스로 건너뛸 수 있다("이벤트 핸들러 멱등성" 참조).
                        body = json.loads(row.payload)
                        body["outbox_event_id"] = row.event_id
                        await sqs_client.send_message(
                            QueueUrl=queue_url,
                            MessageBody=json.dumps(body),
                            MessageAttributes={"eventType": {"DataType": "String", "StringValue": row.event_type}},
                        )
                        row.processed = True
                    except Exception:
                        logger.exception("SQS 발행 실패: event_type=%s event_id=%s", row.event_type, row.event_id)

            await session.commit()
```

`outbox_event_id`를 SQS `MessageBody`에 실어 보내는 시점이 이전(동기 드레인 시절 `OutboxRelay`가 핸들러 호출 직전에 주입)과 달라졌다 — 이제는 `OutboxPoller`가 **발행 시점**에 이미 페이로드에 심어 넣는다. `OutboxConsumer`가 이 값을 그대로 핸들러에 전달하므로, 각 EventHandler 코드는 바뀌지 않았다(`payload["outbox_event_id"]`를 읽는 위치는 동일).

### 3단계: `OutboxConsumer` — SQS → EventHandler 수신 (실제 코드)

`src/outbox/outbox_consumer.py` — `main.py`의 `lifespan`이 마찬가지로 앱 기동 시 단 한 번 띄운다. `receive_message`의 `WaitTimeSeconds`로 long polling하다가 메시지를 받으면 `eventType`(MessageAttributes)으로 `build_event_handlers()`가 조립한 dict에서 핸들러를 찾아 호출한다.

```python
# src/outbox/outbox_consumer.py — 실제 코드(발췌)
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
                raise ValueError("eventType 메시지 속성이 없습니다.")
            payload = json.loads(message.get("Body", "{}"))
            async with self._session_factory() as session:
                handler = build_event_handlers(session).get(event_type)
                if handler is None:
                    raise ValueError(f"등록된 핸들러 없음: {event_type}")
                await handler(payload)
                await session.commit()
            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except Exception:
            logger.exception("이벤트 처리 실패: event_type=%s", event_type)
            # 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
```

핸들러 성공 → `delete_message`로 삭제(ack). 핸들러 실패(또는 등록된 핸들러 없음) → 삭제하지 않는다 → SQS가 visibility timeout 이후 자동 재전달한다(at-least-once) — 이 저장소가 요구하는 EventHandler 멱등성이 바로 이 재전달을 전제한다.

**메시지 하나당 새 `AsyncSession`을 연다** — `_handle_message()`가 `async with self._session_factory() as session:`으로 매번 새 세션을 열고 `build_event_handlers(session)`을 그 자리에서 호출해 Repository/Service 인스턴스를 조립한다. 세션을 Consumer 생성 시점에 한 번만 만들어 계속 재사용하지 않는 이유는 `persistence.md`의 "세션은 단위 작업당 하나" 원칙 — 여러 메시지에 걸쳐 하나의 세션을 공유하면 트랜잭션 경계가 모호해지고 실패한 메시지의 변경사항이 다음 메시지로 새어 나갈 위험이 있다.

### `build_event_handlers()` — 핸들러 라우팅 조립 (실제 코드)

`src/outbox/event_handlers.py`의 `build_event_handlers(session) -> dict[str, Callable]`이 `eventType` 문자열 → 처리 함수의 전체 배선을 조립한다. FastAPI에는 NestJS의 `EventHandlerRegistry` 같은 모듈 간 느슨한 등록 메커니즘이 없으므로, 이 함수 하나가 Account/Card/Payment 세 BC의 composition root다 — `OutboxConsumer`가 메시지를 처리할 때마다 새 세션으로 이 함수를 호출한다("한 번만 조립한다"는 것은 이 조립 로직의 *정의*가 고정되어 있다는 뜻이지, 프로세스 전체에서 세션 하나를 붙잡고 산다는 뜻이 아니다).

```python
# src/outbox/event_handlers.py — 실제 코드(발췌)
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

예전에는 `interface/rest/account_router.py`의 `_outbox_relay()` 팩토리가 **요청마다** 이 조립을 다시 했다(HTTP 요청 스코프 `Depends(get_session)`에 묶여 있었기 때문) — 이제는 `src/outbox/`의 이 함수 하나로 고정되어 `OutboxConsumer`가 메시지마다 재사용한다. `application/event/`의 EventHandler 코드 자체(`account_created_event_handler.py` 등)는 바뀌지 않았다 — payload(dict)를 받아 처리하는 시그니처가 동일하기 때문이다.

`SesNotificationService.notify()`(`src/account/infrastructure/notification/notification_service.py`)는 SES 발송 실패를 잡아 로깅만 하고 삼킨다 — 이 실패는 `OutboxConsumer._handle_message()`의 `try/except`에도 걸리므로, 어느 계층에서 잡히든 결과는 같다: 메시지가 삭제되지 않아 SQS가 재전달한다. `notify()`는 실제 발송 전에 `outbox_event_id` 기준으로 이미 처리된 이벤트인지부터 확인한다(Ledger 멱등성, 아래 참조).

---

## 이벤트 핸들러 멱등성

SQS는 **at-least-once** 전달을 보장하므로 같은 메시지가 중복 수신될 수 있다. 이를 막기 위해 알림을 보내는 EventHandler는 Level 2(Ledger) 멱등성을 적용했다 — `SentEmailModel`(`src/account/infrastructure/notification/sent_email_model.py`)이 발송 이력 Entity이자 Ledger 역할을 겸한다.

Ledger 키는 Outbox 행의 `event_id`다(`account_id`+`event_type`이 아니다 — 같은 계좌에 같은 종류의 이벤트가 여러 번 발생할 수 있으므로 그 조합만으로는 "이 이벤트 한 건"을 특정할 수 없다). `OutboxPoller`가 SQS로 발행하는 시점에 payload에 `outbox_event_id`(= `row.event_id`)를 실어 보내고, `OutboxConsumer`가 이를 그대로 핸들러에 전달하며, 각 EventHandler가 이를 그대로 `NotificationService.notify(event, outbox_event_id)`에 전달한다.

```python
# infrastructure/notification/sent_email_model.py — 실제 코드(발췌)
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
# infrastructure/notification/notification_service.py — 실제 코드(발췌)
async def _send_and_record(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
    event_type = type(event).__name__

    already_sent = (
        await self._session.execute(
            select(SentEmailModel).where(SentEmailModel.outbox_event_id == outbox_event_id)
        )
    ).scalar_one_or_none()
    if already_sent is not None:
        logger.info("이미 발송된 이벤트 — 중복 발송을 건너뜀", extra={...})
        return  # 이미 발송됨 — 중복 스킵

    subject, body = _render(event)
    ...
    self._session.add(SentEmailModel(..., outbox_event_id=outbox_event_id))
    await self._session.flush()
```

check(조회)와 record(저장)가 `notify()` 한 호출 안에서, `OutboxConsumer`가 이 메시지 처리를 위해 새로 연 `AsyncSession`으로 일어난다(Aggregate 저장 트랜잭션과는 완전히 별개의 트랜잭션이다 — 이제 둘은 서로 다른 시점, 다른 프로세스 틱에서 실행된다). Card BC의 반응 핸들러(`SuspendCardsByAccountHandler`/`CancelCardsByAccountHandler`)는 ACTIVE(또는 ACTIVE·SUSPENDED) 카드만 골라 처리하는 Level 1(본질적 멱등)이라 Ledger가 필요 없다 — 알림을 보내는 Account 쪽 EventHandler만 Level 2가 필요했다.

완전한 멱등성 원칙 요약은 root의 [domain-events.md#이벤트-핸들러-멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## Domain Event vs Integration Event

`account`와 `card`, 두 번째 Bounded Context가 생기면서 실제로 Integration Event를 쓰게 되었다. `AccountSuspended`/`AccountClosed` 같은 내부 Domain Event를 그대로 외부로 노출하지 않고, `application/integration_event/`에서 버전이 명시된 별도 계약(`account.suspended.v1`, `account.closed.v1`)으로 변환해 발행한다 — root의 [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참조. 구체적인 흐름은 [cross-domain.md](cross-domain.md)에 정리되어 있다.

```python
# src/account/application/integration_event/account_suspended_integration_event.py — 실제 코드
@dataclass(frozen=True)
class AccountSuspendedIntegrationEventV1:
    event_name: ClassVar[str] = "account.suspended.v1"  # asdict() 페이로드에는 포함되지 않음
    account_id: str
    suspended_at: str
```

변환 지점은 항상 `application/event/`의 EventHandler다(Aggregate가 Integration Event를 직접 만들지 않는다). `application/event/`는 `OutboxWriter`를 직접 쓸 수 있는 유일한 예외이며, 여기서 만든 Integration Event를 같은 세션의 Outbox에 적재한다.

```python
# src/account/application/event/account_suspended_event_handler.py — 실제 코드(발췌)
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

수신 측(Card BC)은 `interface/integration_event/card_integration_event_controller.py`가 담당한다 — HTTP Router와 동일한 위치(`interface/`)의 입력 경계이며, 자기 도메인의 Command Handler만 호출한다. 조립은 `src/outbox/event_handlers.py`의 `build_event_handlers()`가 맡는다 — Account 자신의 6개 Domain Event 핸들러와 Card의 두 Integration Event 반응 핸들러를 같은 `handlers` dict에 등록한다(FastAPI에는 NestJS의 `EventHandlerRegistry` 같은 모듈 간 느슨한 등록 메커니즘이 없다).

이 배선 덕에 `POST /accounts/{id}/suspend` 요청은 다음 순서로 완결된다 — 단, **이제는 하나의 HTTP 요청·트랜잭션 안에서 완결되지 않는다**:
1. HTTP 요청 안에서 `AccountSuspended` Domain Event가 Outbox에 적재되고 응답이 반환된다.
2. 다음 `OutboxPoller` tick(최대 1초 뒤)이 이 행을 SQS로 발행한다.
3. `OutboxConsumer`가 이를 수신해 `AccountSuspendedEventHandler`를 호출 — 알림을 보내고, `account.suspended.v1` Integration Event를 같은(그 메시지 처리를 위해 새로 연) 세션의 Outbox에 추가로 적재한다.
4. 다음 `OutboxPoller` tick이 이 Integration Event도 SQS로 발행한다.
5. `OutboxConsumer`가 이를 수신해 Card BC의 `SuspendCardsByAccountHandler`를 호출 — 연결된 모든 ACTIVE 카드를 SUSPENDED로 바꾼다.

E2E 테스트(`test_card_e2e.py`)는 이 비동기 왕복을 전제로 `wait_until()` 폴링 헬퍼로 최종 상태를 확인한다(`testing.md` 참고) — 응답 직후 즉시 `assert`하면 실패한다.

---

## 원칙

- **이벤트 수집은 Aggregate 안에서만**: `_events` + `pull_events()`로 구현되어 있다.
- **저장과 이벤트 적재는 하나의 트랜잭션**: `SqlAlchemyAccountRepository.save()` 안에서 Aggregate 상태와 Outbox row를 함께 적재하고, 요청 스코프 세션이 한 번에 커밋한다.
- **Command Handler는 저장 후 곧바로 반환한다**: `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 직접 호출하지 않는다 — 동기 드레인은 harness가 `outbox-no-sync-drain` 규칙으로 잡아낸다.
- **Outbox → SQS → Handler 순서로 전달한다**: `OutboxPoller`(발행)와 `OutboxConsumer`(수신·실행)가 서로 독립적으로 주기 실행된다. 폴링 워커(APScheduler/Celery)가 아니라 `main.py`의 `lifespan`이 기동하는 `asyncio.create_task()` 백그라운드 루프다.
- **핸들러는 멱등하게 구현되어 있다**: SQS at-least-once 전달 특성에 대비한다. 알림을 보내는 Account 쪽 EventHandler는 Ledger(`SentEmailModel.outbox_event_id`) 기반 중복 스킵(Level 2)을 적용한다. Card BC의 반응 핸들러(`SuspendCardsByAccountHandler`/`CancelCardsByAccountHandler`)는 ACTIVE(또는 ACTIVE·SUSPENDED) 카드만 골라 처리하므로 재수신에 자연히 멱등하다(Level 1).
- **핸들러 라우팅은 `src/outbox/event_handlers.py`에 고정한다**: `build_event_handlers()`가 Account/Card/Payment 세 BC의 composition root다 — 도메인별 라우터가 요청마다 재조립하지 않는다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의(`events.py`)
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [shared-modules.md](shared-modules.md) — `src/outbox/` 공유 패키지 위치
- [persistence.md](persistence.md) — 트랜잭션 경계
