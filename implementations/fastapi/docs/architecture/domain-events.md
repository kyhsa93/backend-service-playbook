# 도메인 이벤트 발행 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/domain-events.md](../../../../docs/architecture/domain-events.md)

## 현재 구현 — 이벤트 수집은 올바르다

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

## 알려진 격차 — Outbox 없이 동기 발송

`application/command/deposit_handler.py`를 비롯한 모든 Command Handler는 아래 패턴을 따른다.

```python
# 현재 구조 — src/account/application/command/deposit_handler.py
async def execute(self, cmd: DepositCommand) -> Transaction:
    account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
    if account is None:
        raise AccountNotFoundError(cmd.account_id)
    transaction = account.deposit(cmd.amount)
    await self._repo.save(account)                       # 1. DB 커밋
    for event in account.pull_events():                  # 2. 커밋 *이후*, 별도 트랜잭션 없이
        await self._notification_service.notify(event)   #    SES 이메일 발송 시도
    return transaction
```

`SesNotificationService.notify()`(`src/account/infrastructure/notification/notification_service.py`)는 실패를 잡아서 로깅만 하고 삼킨다:

```python
async def notify(self, event: AccountDomainEvent) -> None:
    try:
        await self._send_and_record(event)
    except Exception:  # noqa: BLE001 - 알림 실패는 커맨드 처리에 영향을 주면 안 된다
        logger.exception("알림 이메일 발송 실패: ...")
```

**이것은 의도적인 절충(커맨드 처리 자체가 알림 실패로 실패하면 안 된다)이지만, 대가가 있다.**

- `account.save()`는 성공했는데 프로세스가 `notify()` 호출 직전에 죽으면(재배포, OOM 등), 이벤트는 **영원히 유실**된다 — 재시도 메커니즘이 없다.
- SES 호출이 실패하면 로그에는 남지만, 아무것도 이 실패를 다시 시도하지 않는다 — 사용자는 계좌를 만들었는데 알림 메일을 못 받고, 이 사실을 아무도 재처리하지 않는다.
- at-least-once 전달 보장이 전혀 없다. 이 부분은 root 문서의 Outbox 요구사항을 충족하지 못한다.

**이 문서 작성 시점 기준으로 이 격차는 코드에 남아 있다.** 아래는 root 원칙에 맞는 Outbox 기반 구현이다.

---

## 올바른 패턴 — Outbox 경유

### 1단계: Repository에서 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로

`SqlAlchemyAccountRepository.save()`(`src/account/infrastructure/persistence/account_repository.py`)는 이미 `AccountModel`/`TransactionModel`을 같은 `AsyncSession`에 `add()`하고 있다 — Outbox 테이블도 같은 세션에 추가하면 자연스럽게 원자적이 된다.

```python
# infrastructure/persistence/account_repository.py — 수정 제안
import json

from .outbox_model import OutboxModel  # 신설 제안


class SqlAlchemyAccountRepository(AccountRepository):
    async def save(self, account: Account) -> None:
        existing = await self._session.get(AccountModel, account.account_id)
        if existing:
            existing.amount = account.balance.amount
            existing.status = account.status.value
        else:
            self._session.add(AccountModel(id=account.account_id, ...))

        for transaction in account.pull_pending_transactions():
            self._session.add(TransactionModel(...))

        for event in account.pull_events():
            self._session.add(OutboxModel(
                event_id=generate_id(),
                event_type=type(event).__name__,
                payload=json.dumps(dataclasses.asdict(event), default=str),
                processed=False,
            ))

        await self._session.flush()   # 커밋은 get_session()의 컨텍스트 종료 시점에 일어남 — Aggregate와 Outbox가 같은 트랜잭션
```

이렇게 하면 `deposit_handler.py`는 더 이상 `notify()`를 직접 호출하지 않는다 — 이벤트 수집·저장은 Repository의 책임이 되고, Handler는 Aggregate에 위임하는 역할만 남는다.

```python
# application/command/deposit_handler.py — Outbox 적용 후
async def execute(self, cmd: DepositCommand) -> Transaction:
    account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
    if account is None:
        raise AccountNotFoundError(cmd.account_id)
    transaction = account.deposit(cmd.amount)
    await self._repo.save(account)   # Outbox 적재까지 포함 — notify() 직접 호출 제거
    return transaction
```

### Outbox 테이블 스키마

```python
# infrastructure/persistence/outbox_model.py (신설 제안)
from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column

from .account_repository import Base


class OutboxModel(Base):
    __tablename__ = "outbox"

    event_id: Mapped[str] = mapped_column(primary_key=True)
    event_type: Mapped[str]                 # 예: "MoneyDeposited"
    payload: Mapped[str]                    # JSON 직렬화
    processed: Mapped[bool] = mapped_column(default=False)
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
```

### 2단계: Outbox → 알림 발송 (폴링 워커)

```python
# infrastructure/notification/outbox_relay.py (신설 제안) — 별도 프로세스 또는 APScheduler 잡
import json

from sqlalchemy import select

from .notification_service import SesNotificationService
from ..persistence.outbox_model import OutboxModel


async def relay_pending_events(session, notification_service: SesNotificationService) -> None:
    stmt = select(OutboxModel).where(OutboxModel.processed.is_(False)).limit(100)
    rows = (await session.execute(stmt)).scalars().all()

    for row in rows:
        try:
            event = _deserialize(row.event_type, json.loads(row.payload))
            await notification_service.notify(event)
            row.processed = True
        except Exception:
            logger.exception("outbox 재처리 실패: event_id=%s", row.event_id)
            # processed=False로 남겨두어 다음 폴링에서 재시도 — at-least-once
    await session.commit()
```

이 워커는 `scheduling.md`에서 다루는 백그라운드 작업 패턴(APScheduler)으로 짧은 주기(예: 5초)마다 실행한다. `notify()`가 실패해도 `processed`가 `False`로 남아 다음 주기에 자동 재시도되므로, "커밋 후 죽으면 유실"이라는 현재의 근본 문제가 해결된다.

---

## 이벤트 핸들러 멱등성

Outbox 도입 후 `notify()`는 **at-least-once**로 호출될 수 있다(워커가 `row.processed = True`를 커밋하기 직전에 죽으면 같은 이벤트가 다시 발송된다). `SentEmailModel`(`src/account/infrastructure/notification/sent_email_model.py`)에 이미 `event_type` + `account_id` 컬럼이 있으므로, 중복 발송을 막으려면 발송 전에 already-sent 여부를 조회하는 Level 2(Ledger) 멱등성을 적용한다.

```python
# _send_and_record() 진입 시 추가
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
- **저장과 이벤트 적재는 하나의 트랜잭션**: Repository의 `save()` 안에서 Aggregate 상태와 Outbox row를 함께 커밋한다.
- **후속 처리는 Outbox 폴링을 통해서만**: Command Handler가 알림 서비스를 직접 호출하지 않는다.
- **핸들러는 멱등하게**: at-least-once 전달을 전제로, Ledger(`SentEmailModel`)로 중복 발송을 방지한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의(`events.py`)
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [scheduling.md](scheduling.md) — Outbox Relay를 실행하는 백그라운드 작업 패턴
- [persistence.md](persistence.md) — 트랜잭션 경계
