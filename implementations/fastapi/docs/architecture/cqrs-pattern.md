# CQRS Pattern

> Framework-agnostic principles: [../../../../docs/architecture/cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md)

This repository's FastAPI example (`examples/src/account/`) already applies a **lightweight form of Handler-based CQRS**: Command and Query are each split into a separate `Handler` class + `execute()` method, and the QueryHandler depends only on the read-only `AccountQuery` interface, not the write-capable `AccountRepository`. This document describes the current structure and provides a path for expanding toward a Command/Query Bus once the number of use cases grows.

---

## Current structure — a Handler class + `execute()`

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
      result.py                   ← GetAccountResult, GetTransactionsResult, etc.
```

A Command/Query is an immutable input object defined with `@dataclass`; a Handler is a thin class that receives its dependencies via `__init__` and executes via `async def execute(self, cmd/query)`.

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
        await self._repo.save(account)          # commits the Aggregate save + Outbox load in the same transaction
        return transaction   # returns immediately after saving — draining the Outbox is OutboxPoller/OutboxConsumer's job
```

**Flow:** look up the Aggregate from the Repository → call the Aggregate's domain method (`account.deposit()`) → save via the Repository (commits the Aggregate state + the Outbox row in one transaction) → return immediately. Publishing/receiving from Outbox → SQS is handled by the independently, periodically running `OutboxPoller`/`OutboxConsumer` (the Command Handler never calls these — the harness's `outbox-no-sync-drain` rule catches a violation). The Handler itself has no business rules and delegates to the Aggregate — see [layer-architecture.md](layer-architecture.md), [domain-events.md](domain-events.md).

### Query + QueryHandler — depends only on the read-only `AccountQuery`

A QueryHandler depends on the read-only `AccountQuery` interface, not the write-capable `AccountRepository` (ABC, includes `save()`). `AccountQuery` is a separate ABC defined alongside it in `domain/repository.py`, declaring only the methods a QueryHandler actually calls, such as `find_accounts`/`find_transactions`, without exposing `save()`.

```python
# src/account/domain/repository.py
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """Read-only interface — for the Query Handler only. Never exposes a write method such as save()."""

    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
    """The write model — extends AccountQuery to reuse its lookup methods, and adds write methods such as save()."""

    @abstractmethod
    async def save(self, account: Account) -> None: ...
```

Since `AccountRepository` extends `AccountQuery`, its implementation `SqlAlchemyAccountRepository(AccountRepository)` can be injected as-is as either type without needing a new separate class satisfying both ABCs — no code change is needed in `infrastructure/persistence/account_repository.py`.

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

`GetTransactionsHandler` likewise depends on `AccountQuery`. A QueryHandler never returns the domain Aggregate directly — it converts it into `GetAccountResult` (`src/account/application/query/result.py`) — see [api-response.md](api-response.md).

The Depends factories in `interface/rest/account_router.py` are also split per Command/Query path, so a Query endpoint is injected only with the `AccountQuery` type.

```python
# src/account/interface/rest/account_router.py — actual code
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

The object actually injected at runtime is still a `SqlAlchemyAccountRepository` (which also has `save()`), but since the static type declared by `get_account`'s `repo` parameter and `GetAccountHandler.__init__` is only `AccountQuery`, the Query-path code can't access `save()` even from the type checker's point of view — this is the point where CQRS separation is actually enforced.

### The Interface layer — direct assembly, no Bus

There is currently no Command/Query Bus. The Depends factories in `interface/rest/account_router.py` instantiate the Handler directly. As shown below, the Command path uses `_repo` (the write type `SqlAlchemyAccountRepository`), and the Query path uses the `_query_repo` seen earlier (the read type `AccountQuery`) — even though the same `SqlAlchemyAccountRepository` instance gets constructed, the static type exposed in the route function's signature differs.

```python
# src/account/interface/rest/account_router.py — actual code
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

This lightweight form is sufficient when the number of use cases is small. The Router effectively acts as the "Bus," and it's immediately visible in the code exactly which Handler each route calls.

---

## Expansion — criteria for introducing a Command/Query Bus

| Situation | Recommendation |
|---|---|
| The Handler-assembly code repeats across routers and the router grows bloated | Consider introducing a Bus |
| Common middleware (logging, transactions, authorization) needs to be applied uniformly across multiple Handlers | Consider introducing a Bus |
| The number of use cases is small and routers are simple, as now | The current structure is sufficient |

The Python/FastAPI ecosystem has no standard Bus framework like NestJS's `@nestjs/cqrs`, so a thin registry is written directly if needed.

```python
# src/common/command_bus.py — concept (does not exist in this repository yet)
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

Even after introducing a Bus, the Handler class itself (input = a `@dataclass` Command/Query, an `execute()` method) doesn't change — only the routing mechanism changes.

---

## EventHandler — Domain Event follow-up processing

A Command Handler never calls `notify()` directly — `repo.save()` bundles the Aggregate save and the Outbox load into a single transaction, and the Handler returns immediately after saving (it never calls `OutboxPoller`/`OutboxConsumer` directly). Publishing/receiving from Outbox → SQS is handled by the independently, periodically running `OutboxPoller`/`OutboxConsumer`, and the follow-up processing per `event_type` (currently notification sending; the same applies as audit logging, statistics aggregation, etc. are added in the future) is split into `application/event/<event>_event_handler.py`.

→ See [domain-events.md](domain-events.md) for implementation details.

---

## Principles

- **Command/Query separation**: writes go in `application/command/`, reads in `application/query/`.
- **Query never references the write-capable Repository**: a QueryHandler depends only on the read-only `AccountQuery`, not `AccountRepository` (which includes `save()`) — the Depends factories in `interface/rest/account_router.py` also expose only the `AccountQuery` type to Query endpoints.
- **The Handler only orchestrates**: business logic is delegated to the Aggregate (`domain/account.py`).
- **Input is an immutable `@dataclass`**: `CreateAccountCommand`, `GetAccountQuery`, etc.
- **A Query returns a Result object**: never serializes the domain Aggregate directly.
- **Introduce a Bus only when needed**: with few use cases, direct assembly via a Depends factory is sufficient.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — the base layer structure (the foundation for Command/Query separation)
- [domain-events.md](domain-events.md) — the EventHandler, the Outbox pattern
- [repository-pattern.md](repository-pattern.md) — the Repository pattern
- [api-response.md](api-response.md) — Result object design
