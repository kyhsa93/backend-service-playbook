# CQRS 패턴

> 프레임워크 무관 원칙: [../../../../docs/architecture/cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md)

이 저장소의 FastAPI 예시(`examples/src/account/`)는 이미 **Handler 기반 CQRS의 경량 형태**를 적용하고 있다: Command와 Query를 각각 별도 `Handler` 클래스 + `execute()` 메서드로 분리하고, QueryHandler는 쓰기용 `AccountRepository`가 아니라 읽기 전용 `AccountQuery` 인터페이스에만 의존한다. 이 문서는 현재 구조를 설명하고, 유스케이스가 늘어났을 때 Command/Query Bus로 확장하는 경로를 안내한다.

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


@dataclass
class DepositCommand:
    account_id: str
    requester_id: str
    amount: int


class DepositHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DepositCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)
        await self._repo.save(account)          # Aggregate 저장 + Outbox 적재를 같은 트랜잭션으로 커밋
        return transaction   # 저장 후 곧바로 반환 — Outbox 드레인은 OutboxPoller/OutboxConsumer의 몫
```

**흐름:** Repository에서 Aggregate 조회 → Aggregate의 도메인 메서드 호출(`account.deposit()`) → Repository로 저장(Aggregate 상태 + Outbox 행을 한 트랜잭션에 커밋) → 곧바로 반환. Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 `OutboxPoller`/`OutboxConsumer`가 처리한다(Command Handler는 이를 호출하지 않는다 — harness의 `outbox-no-sync-drain` 규칙이 위반을 잡아낸다). Handler 자신은 비즈니스 규칙을 갖지 않고 Aggregate에 위임한다 — [layer-architecture.md](layer-architecture.md), [domain-events.md](domain-events.md) 참조.

### Query + QueryHandler — 읽기 전용 `AccountQuery`에만 의존

QueryHandler는 쓰기용 `AccountRepository`(ABC, `save()` 포함)가 아니라 읽기 전용 `AccountQuery` 인터페이스에 의존한다. `AccountQuery`는 `domain/repository.py`에 함께 정의된 별도 ABC로, `find_accounts`/`find_transactions`처럼 QueryHandler가 실제로 호출하는 메서드만 선언하고 `save()`는 노출하지 않는다.

```python
# src/account/domain/repository.py
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """읽기 전용 인터페이스 — Query Handler 전용. save() 등 쓰기 메서드를 노출하지 않는다."""

    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
    """쓰기 모델 — AccountQuery를 상속해 조회 메서드를 재사용하되 save() 등 쓰기 메서드를 추가한다."""

    @abstractmethod
    async def save(self, account: Account) -> None: ...
```

`AccountRepository`가 `AccountQuery`를 상속하므로, 구현체 `SqlAlchemyAccountRepository(AccountRepository)`는 두 ABC를 모두 만족하는 별도 클래스를 새로 만들 필요 없이 그대로 양쪽 타입으로 주입될 수 있다 — `infrastructure/persistence/account_repository.py`에는 코드 변경이 필요 없다.

```python
# src/account/application/query/get_account_handler.py
from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError
from ...domain.repository import AccountQuery
from .result import GetAccountResult, MoneyResult


@dataclass
class GetAccountQuery:
    account_id: str
    requester_id: str


class GetAccountHandler:
    def __init__(self, repo: AccountQuery) -> None:
        self._repo = repo

    async def execute(self, query: GetAccountQuery) -> GetAccountResult:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=query.account_id, owner_id=query.requester_id)
        account = accounts[0] if accounts else None
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

`GetTransactionsHandler`도 동일하게 `AccountQuery`에 의존한다. QueryHandler는 도메인 Aggregate를 직접 반환하지 않고 `GetAccountResult`(`src/account/application/query/result.py`)로 변환한다 — [api-response.md](api-response.md) 참조.

`interface/rest/account_router.py`의 Depends 팩토리도 Command/Query 경로별로 분리되어, Query 엔드포인트는 `AccountQuery` 타입만 주입받는다.

```python
# src/account/interface/rest/account_router.py — 실제 코드
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _query_repo(session: AsyncSession = Depends(get_session)) -> AccountQuery:
    return SqlAlchemyAccountRepository(session)


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    repo: AccountQuery = Depends(_query_repo),
) -> GetAccountResponse:
    result = await GetAccountHandler(repo).execute(
        GetAccountQuery(account_id=account_id, requester_id=current_user.user_id)
    )
    ...
```

런타임에 주입되는 객체는 여전히 `SqlAlchemyAccountRepository`(`save()`도 갖고 있다)지만, `get_account`의 `repo` 파라미터와 `GetAccountHandler.__init__`이 선언한 정적 타입은 `AccountQuery`뿐이므로 Query 경로 코드는 타입 체커 상으로도 `save()`에 접근할 수 없다 — 이것이 CQRS 분리가 실제로 강제되는 지점이다.

### Interface 레이어 — Bus 없이 직접 조립

현재는 Command/Query Bus가 없다. `interface/rest/account_router.py`의 Depends 팩토리가 Handler를 직접 인스턴스화한다. Command 경로는 아래처럼 `_repo`(쓰기 타입 `SqlAlchemyAccountRepository`)를 사용하고, Query 경로는 앞서 본 `_query_repo`(읽기 타입 `AccountQuery`)를 사용한다 — 같은 `SqlAlchemyAccountRepository` 인스턴스가 만들어지더라도 라우트 함수 시그니처에 노출되는 정적 타입이 다르다.

```python
# src/account/interface/rest/account_router.py — 실제 코드
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


@router.post("/{account_id}/deposit", status_code=201, response_model=TransactionResponse)
async def deposit(
    account_id: str,
    body: DepositRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    transaction = await DepositHandler(repo).execute(
        DepositCommand(account_id=account_id, requester_id=current_user.user_id, amount=body.amount)
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

Command Handler는 `notify()`를 직접 호출하지 않는다 — `repo.save()`가 Aggregate 저장과 Outbox 적재를 하나의 트랜잭션으로 묶고, Handler는 저장 후 곧바로 반환한다(`OutboxPoller`/`OutboxConsumer`를 직접 호출하지 않는다). Outbox → SQS 발행/수신은 독립적으로 주기 실행되는 `OutboxPoller`/`OutboxConsumer`가 처리하며, `event_type`별 후속 처리(현재는 알림 발송, 향후 감사 로그·통계 집계 등이 늘어나도 마찬가지)는 `application/event/<event>_event_handler.py`로 분리되어 있다.

→ 상세 구현은 [domain-events.md](domain-events.md) 참조.

---

## 원칙

- **Command/Query 분리**: 쓰기는 `application/command/`, 읽기는 `application/query/`.
- **Query는 쓰기용 Repository를 참조하지 않는다**: QueryHandler는 `AccountRepository`(`save()` 포함)가 아니라 읽기 전용 `AccountQuery`에만 의존한다 — `interface/rest/account_router.py`의 Depends 팩토리도 Query 엔드포인트에는 `AccountQuery` 타입만 노출한다.
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
