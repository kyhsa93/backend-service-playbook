# CQRS 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md)

이 저장소의 FastAPI 예시(`examples/src/account/`)는 이미 **Handler 기반 CQRS의 경량 형태**를 적용하고 있다: Command와 Query를 각각 별도 `Handler` 클래스 + `execute()` 메서드로 분리한다. 이 문서는 현재 구조를 설명하고, 유스케이스가 늘어났을 때 Command/Query Bus로 확장하는 경로를 안내한다.

---

## 현재 구조 — Handler 클래스 + `execute()`

```
src/account/
  application/
    command/
      create_account_handler.py    ← CreateAccountCommand + CreateAccountHandler
      deposit_handler.py           ← DepositCommand + DepositHandler
      withdraw_handler.py          ← WithdrawCommand + WithdrawHandler
      suspend_account_handler.py
      reactivate_account_handler.py
      close_account_handler.py
    query/
      get_account_handler.py       ← GetAccountQuery + GetAccountHandler
      get_transactions_handler.py  ← GetTransactionsQuery + GetTransactionsHandler
      result.py                   ← GetAccountResult, GetTransactionsResult 등
```

Command/Query는 `@dataclass`로 정의한 불변 입력 객체, Handler는 `__init__`으로 의존성을 받고 `async def execute(self, cmd/query)`로 실행하는 얇은 클래스다.

### Command + CommandHandler

```python
# src/account/application/command/deposit_handler.py
from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from ...domain.transaction import Transaction
from ....outbox.outbox_relay import OutboxRelay


@dataclass
class DepositCommand:
    account_id: str
    requester_id: str
    amount: int


class DepositHandler:
    def __init__(self, repo: AccountRepository, outbox_relay: OutboxRelay) -> None:
        self._repo = repo
        self._outbox_relay = outbox_relay

    async def execute(self, cmd: DepositCommand) -> Transaction:
        account = await self._repo.find_by_id(cmd.account_id, cmd.requester_id)
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)
        await self._repo.save(account)          # Aggregate 저장 + Outbox 적재를 같은 트랜잭션으로 커밋
        await self._outbox_relay.process_pending()  # 커밋 직후 동기적으로 Outbox 드레인
        return transaction
```

**흐름:** Repository에서 Aggregate 조회 → Aggregate의 도메인 메서드 호출(`account.deposit()`) → Repository로 저장(Aggregate 상태 + Outbox 행을 한 트랜잭션에 커밋) → 저장 직후 `OutboxRelay.process_pending()`으로 드레인. Handler 자신은 비즈니스 규칙을 갖지 않고 Aggregate에 위임한다 — [layer-architecture.md](layer-architecture.md), [domain-events.md](domain-events.md) 참조.

### Query + QueryHandler

```python
# src/account/application/query/get_account_handler.py
from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountRepository
from .result import GetAccountResult, MoneyResult


@dataclass
class GetAccountQuery:
    account_id: str
    requester_id: str


class GetAccountHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetAccountQuery) -> GetAccountResult:
        account = await self._repo.find_by_id(query.account_id, query.requester_id)
        if account is None:
            raise AccountNotFoundError(query.account_id)
        return GetAccountResult(
            account_id=account.account_id,
            owner_id=account.owner_id,
            email=account.email,
            balance=MoneyResult(amount=account.balance.amount, currency=account.balance.currency),
            status=account.status.value,
            created_at=account.created_at,
            updated_at=account.updated_at,
        )
```

QueryHandler는 도메인 Aggregate를 직접 반환하지 않고 `GetAccountResult`(`src/account/application/query/result.py`)로 변환한다 — [api-response.md](api-response.md) 참조.

### Interface 레이어 — Bus 없이 직접 조립

현재는 Command/Query Bus가 없다. `interface/rest/account_router.py`의 Depends 팩토리가 Handler를 직접 인스턴스화한다.

```python
# src/account/interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    x_user_id: str = Header(...),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
    outbox_relay: OutboxRelay = Depends(_outbox_relay),
) -> TransactionResponse:
    transaction = await DepositHandler(repo, outbox_relay).execute(
        DepositCommand(account_id=account_id, requester_id=x_user_id, amount=body.amount)
    )
    return TransactionResponse(...)
```

이 경량 형태는 유스케이스 수가 적을 때 충분하다. Router가 곧 "Bus" 역할을 하며, 라우트마다 정확히 어떤 Handler가 호출되는지 코드에서 바로 보인다.

---

## 확장 — Command/Query Bus 도입 기준

| 상황 | 권장 |
|---|---|
| Handler 조립 코드가 라우터마다 반복되고 라우터가 비대해질 때 | Bus 도입 검토 |
| 여러 Handler에 공통 미들웨어(로깅, 트랜잭션, 인가)를 일괄 적용하고 싶을 때 | Bus 도입 검토 |
| 현재처럼 유스케이스 수가 적고 라우터가 단순할 때 | 현재 구조로 충분 |

Python/FastAPI 생태계에는 NestJS의 `@nestjs/cqrs` 같은 표준 Bus 프레임워크가 없으므로, 직접 얇은 레지스트리를 둔다.

```python
# src/common/command_bus.py — 개념 (아직 이 저장소에 존재하지 않음)
from typing import Any, Callable, TypeVar

TCommand = TypeVar("TCommand")
TResult = TypeVar("TResult")


class CommandBus:
    def __init__(self) -> None:
        self._handlers: dict[type, Callable[[Any], Any]] = {}

    def register(self, command_type: type[TCommand], handler_factory: Callable[[], Any]) -> None:
        self._handlers[command_type] = handler_factory

    async def execute(self, command: TCommand) -> Any:
        handler = self._handlers[type(command)]()
        return await handler.execute(command)
```

Bus를 도입해도 Handler 클래스 자체(입력 = `@dataclass` Command/Query, `execute()` 메서드)는 변하지 않는다 — 달라지는 것은 라우팅 방식뿐이다.

---

## EventHandler — Domain Event 후속 처리

Command Handler는 `notify()`를 직접 호출하지 않는다 — `repo.save()`가 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로 묶고, Handler는 그 직후 `OutboxRelay.process_pending()`을 한 번 호출해 Outbox를 드레인할 뿐이다. `event_type`별 후속 처리(현재는 알림 발송, 향후 감사 로그·통계 집계 등이 늘어나도 마찬가지)는 `application/event/<event>_event_handler.py`로 분리되어 있다.

→ 상세 구현은 [domain-events.md](domain-events.md) 참조.

---

## 원칙

- **Command/Query 분리**: 쓰기는 `application/command/`, 읽기는 `application/query/`.
- **Handler는 조율만**: 비즈니스 로직은 Aggregate(`domain/account.py`)에 위임한다.
- **입력은 불변 `@dataclass`**: `CreateAccountCommand`, `GetAccountQuery` 등.
- **Query는 Result 객체 반환**: 도메인 Aggregate를 직접 직렬화하지 않는다.
- **Bus는 필요할 때만 도입**: 유스케이스가 적으면 Depends 팩토리로 직접 조립해도 충분하다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 기본 레이어 구조 (Command/Query 분리의 기반)
- [domain-events.md](domain-events.md) — EventHandler, Outbox 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 패턴
- [api-response.md](api-response.md) — Result 객체 설계
