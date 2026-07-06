# 도메인 이벤트 발행 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/domain-events.md](../../../../docs/architecture/domain-events.md)

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

이전에는 `repo.save()`가 성공한 직후 커맨드 핸들러가 `notification_service.notify()`를 직접 호출하는 dual-write 구조였다 — 저장과 알림 발송 사이에 프로세스가 죽으면 이벤트가 영원히 유실될 수 있었다. 지금은 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로 묶고, 드레인이 실패해도 다음 커맨드에서 자동 재시도되도록 구현되어 있다 — root 문서의 Outbox 요구사항을 충족한다.

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
            self._session.add(OutboxModel(
                event_id=uuid.uuid4().hex,
                event_type=type(event).__name__,
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

`Base`는 `src/account/infrastructure/persistence/account_repository.py`에 정의된 것을 그대로 import해서 재사용한다 — `Base.metadata.create_all`(`main.py`의 `lifespan`)이 `outbox` 테이블도 자동으로 만든다.

### 2단계: Command Handler가 `repo.save()` 직후 Outbox를 동기적으로 드레인한다

nestjs 구현과 동일한 트리거 전략을 쓴다 — **폴링 워커(APScheduler/Celery) 없이**, 커맨드를 처리하는 바로 그 요청 안에서 `repo.save()`가 반환한 직후 `OutboxRelay.process_pending()`을 한 번 호출한다. 6개 Command Handler(`create_account_handler.py`, `deposit_handler.py`, `withdraw_handler.py`, `suspend_account_handler.py`, `reactivate_account_handler.py`, `close_account_handler.py`) 모두 같은 패턴이다.

```python
# application/command/deposit_handler.py — 실제 코드
class DepositHandler:

    def __init__(self, repo: AccountRepository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: DepositCommand) -> Transaction:
        account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)
        await self._repo.save(account)          # Aggregate 저장 + Outbox 적재 (하나의 트랜잭션)
        await self._outbox_relay.process_pending()   # 드레인 — 이 커맨드의 이벤트만이 아니라 테이블 전체
        return transaction
```

이 요청의 `AsyncSession`은 `Depends(get_session)`의 요청 스코프 캐싱으로 Repository/`OutboxRelay`/`NotificationService`가 모두 공유한다(`persistence.md` 참조). 따라서 Aggregate 저장, Outbox 적재, 이벤트 드레인(알림 발송 기록 포함)이 전부 같은 트랜잭션에 속하고, 라우트 함수가 반환된 뒤 `get_session()`의 `await session.commit()` 한 번으로 전부 커밋된다. 드레인 도중 프로세스가 죽으면 커밋 자체가 일어나지 않아 계좌 상태 변경도 함께 롤백되므로 — "계좌는 바뀌었는데 알림만 유실"되는 상황 자체가 발생하지 않는다.

`OutboxRelay.process_pending()`(`src/outbox/outbox_relay.py`)은 자신이 방금 적재한 이벤트뿐 아니라 테이블 전체의 `processed=False` row를 훑는다 — 그래서 어떤 커맨드 처리 중 핸들러가 실패해 row가 미처리로 남아도, **다음에 어떤 종류의 커맨드든 한 번 더 실행되는 순간** 자동으로 재시도된다.

```python
# src/outbox/outbox_relay.py — 실제 코드
class OutboxRelay:
    def __init__(self, session: AsyncSession, handlers: dict[str, Callable[[dict], Awaitable[None]]]) -> None:
        self._session = session
        self._handlers = handlers

    async def process_pending(self) -> None:
        stmt = select(OutboxModel).where(OutboxModel.processed.is_(False))
        rows = (await self._session.execute(stmt)).scalars().all()

        for row in rows:
            handler = self._handlers.get(row.event_type)
            try:
                if handler is not None:
                    await handler(json.loads(row.payload))
                row.processed = True
            except Exception:  # noqa: BLE001 - 다음 드레인에서 재시도해야 하므로 여기서 삼킨다
                logger.exception("이벤트 처리 실패: event_type=%s event_id=%s", row.event_type, row.event_id)

        await self._session.flush()
```

`process_pending()`은 절대 예외를 밖으로 던지지 않는다 — 핸들러가 실패해도 로깅만 하고 다음 row로 넘어가며, 실패한 row는 `processed=False`로 남는다.

### 3단계: `application/event/`의 EventHandler가 페이로드를 역직렬화해 `NotificationService`를 호출한다

`event_type`별로 하나씩, `account_created_event_handler.py`/`money_deposited_event_handler.py`/`money_withdrawn_event_handler.py`/`account_suspended_event_handler.py`/`account_reactivated_event_handler.py`/`account_closed_event_handler.py`가 있다. 각 핸들러는 Outbox에 저장된 JSON 페이로드(`dict`)를 원래의 frozen dataclass(`AccountDomainEvent`)로 복원한 뒤, 기존 `NotificationService.notify()`를 그대로 호출한다 — 이메일 제목/본문 렌더링(`_render()`)과 발송 기록(`SentEmailModel`) 로직은 전혀 바뀌지 않았다.

```python
# application/event/money_deposited_event_handler.py — 실제 코드
class MoneyDepositedEventHandler:

    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = MoneyDeposited(
            account_id=payload["account_id"],
            transaction_id=payload["transaction_id"],
            email=payload["email"],
            amount=Money(**payload["amount"]),
            balance_after=Money(**payload["balance_after"]),
            created_at=datetime.fromisoformat(payload["created_at"]),
        )
        await self._notification_service.notify(event)
```

`interface/rest/account_router.py`의 `_outbox_relay()` 팩토리가 이 6개 핸들러를 `event_type` 문자열 → `handle` 메서드의 `dict` 레지스트리로 조립해 `OutboxRelay`에 주입한다.

```python
# interface/rest/account_router.py — 실제 코드
def _outbox_relay(
    session: AsyncSession = Depends(get_session),
    notification_service: NotificationService = Depends(_notification_service),
) -> OutboxRelay:
    return OutboxRelay(
        session=session,
        handlers={
            "AccountCreated": AccountCreatedEventHandler(notification_service).handle,
            "MoneyDeposited": MoneyDepositedEventHandler(notification_service).handle,
            "MoneyWithdrawn": MoneyWithdrawnEventHandler(notification_service).handle,
            "AccountSuspended": AccountSuspendedEventHandler(notification_service).handle,
            "AccountReactivated": AccountReactivatedEventHandler(notification_service).handle,
            "AccountClosed": AccountClosedEventHandler(notification_service).handle,
        },
    )
```

`SesNotificationService.notify()`(`src/account/infrastructure/notification/notification_service.py`)는 이전과 동일하게 SES 발송 실패를 잡아 로깅만 하고 삼킨다 — 다만 이제 이 실패는 `OutboxRelay.process_pending()`의 `try/except`에도 걸리므로, 어느 계층에서 잡히든 결과는 같다: Outbox row가 `processed=False`로 남아 다음 드레인에서 재시도된다.

---

## 이벤트 핸들러 멱등성 — 남은 개선 지점

Outbox 도입으로 `notify()`는 **at-least-once**로 호출될 수 있다(`OutboxRelay`가 `row.processed = True`로 표시하기 직전에 죽으면 같은 이벤트가 다음 드레인에서 다시 발송된다). `SentEmailModel`(`src/account/infrastructure/notification/sent_email_model.py`)에 이미 `event_type` + `account_id` 컬럼이 있으므로, 중복 발송을 막으려면 발송 전에 already-sent 여부를 조회하는 Level 2(Ledger) 멱등성을 추가로 적용할 수 있다. **이 문서 작성 시점 기준으로 이 멱등성 체크는 아직 코드에 없다** — Outbox 자체의 원자성(계좌 저장과 이벤트 적재가 항상 같이 커밋됨)은 이미 보장되므로, 이는 "이벤트 유실"이 아니라 "드문 상황에서 같은 이메일이 두 번 발송될 수 있다"는 별개의, 덜 심각한 격차다.

```python
# _send_and_record() 진입 시 추가하면 되는 코드 (아직 미적용)
existing = await self._session.execute(
    select(SentEmailModel).where(
        SentEmailModel.account_id == event.account_id,
        SentEmailModel.event_type == event_type,
    )
)
if existing.scalar_one_or_none() is not None:
    return  # 이미 발송됨 — 중복 스킵
```

완전한 멱등성 원칙 요약은 root의 [domain-events.md#이벤트-핸들러-멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## Domain Event vs Integration Event

이 저장소는 단일 Bounded Context(`account`)만 있으므로 아직 Integration Event가 필요 없다. 두 번째 BC(예: `notification` 서비스를 별도 프로세스로 분리)를 추가하게 되면, `AccountCreated` 같은 Domain Event를 그대로 외부로 노출하지 말고 `application/integration-event/`에서 버전이 명시된 별도 계약(`account.created.v1`)으로 변환해야 한다 — root의 [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) 참조.

---

## 원칙

- **이벤트 수집은 Aggregate 안에서만**: `_events` + `pull_events()` — 이미 올바르게 구현되어 있다.
- **저장과 이벤트 적재는 하나의 트랜잭션**: `SqlAlchemyAccountRepository.save()` 안에서 Aggregate 상태와 Outbox row를 함께 적재하고, 요청 스코프 세션이 한 번에 커밋한다.
- **후속 처리는 Outbox 드레인을 통해서만**: Command Handler는 알림 서비스를 직접 호출하지 않고 `OutboxRelay.process_pending()`만 호출한다.
- **드레인은 폴링 워커가 아니라 다음 커맨드가 트리거한다**: 배경 작업(APScheduler/Celery) 없이도 매 커맨드가 테이블 전체를 훑어 재시도까지 자연스럽게 처리한다 — e2e 테스트에서 타이밍 대기가 필요 없는 이유이기도 하다.
- **핸들러는 (아직) 멱등하지 않다**: at-least-once 전달을 전제로 하지만, Ledger(`SentEmailModel`) 기반 중복 스킵은 아직 미적용 — 남은 개선 지점.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의(`events.py`)
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [shared-modules.md](shared-modules.md) — `src/outbox/` 공유 패키지 위치
- [persistence.md](persistence.md) — 트랜잭션 경계
