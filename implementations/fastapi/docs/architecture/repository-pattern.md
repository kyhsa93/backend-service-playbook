# Repository Pattern

> Framework-agnostic principles: [../../../../docs/architecture/repository-pattern.md](../../../../docs/architecture/repository-pattern.md)

## One Repository per Aggregate Root

There is only one implementation for the Account Aggregate (`SqlAlchemyAccountRepository`), but the interface (ABC) is split into two: the write model `AccountRepository` and the read-only `AccountQuery` — a CommandHandler depends on `AccountRepository`, a QueryHandler on `AccountQuery`. See [cqrs-pattern.md](cqrs-pattern.md) for the detailed background.

```
src/account/
  domain/
    repository.py             ← AccountQuery(ABC, read-only) + AccountRepository(ABC, extends AccountQuery + write) — interfaces
  infrastructure/
    persistence/
      account_repository.py   ← SqlAlchemyAccountRepository(AccountRepository) — implementation, also satisfies AccountQuery
```

```python
# domain/repository.py
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountQuery(ABC):
    """Read-only interface — for the Query Handler only."""

    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def find_transactions(self, account_id: str, page: int, take: int) -> tuple[list[Transaction], int]: ...


class AccountRepository(AccountQuery, ABC):
    """Write model — extends AccountQuery to reuse the lookup methods."""

    @abstractmethod
    async def save_account(self, account: Account) -> None: ...
```

Lookups are unified under a single `find_accounts` method — a single-item lookup is done by calling it with filters (`account_id`+`owner_id`) and `take=1`, then pulling the first item out of the result list:

```python
# application/command/deposit_handler.py — single-item lookup pattern
async def execute(self, cmd: DepositCommand) -> Transaction:
    accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
    account = accounts[0] if accounts else None
    if account is None:
        raise AccountNotFoundError(cmd.account_id)
    ...
```

Every call site that looks up a single account — `deposit_handler.py`/`withdraw_handler.py`/`suspend_account_handler.py`/`reactivate_account_handler.py`/`close_account_handler.py`/`get_account_handler.py`/`get_transactions_handler.py`, etc. — follows this pattern. It has the same shape as java/kotlin-springboot's `findAccounts` pattern.

A CommandHandler is injected with the `AccountRepository` type (ABC), a QueryHandler with the `AccountQuery` type (ABC) — neither imports `SqlAlchemyAccountRepository` directly. The DI binding is handled by a FastAPI `Depends` factory: see [layer-architecture.md](layer-architecture.md), [cqrs-pattern.md](cqrs-pattern.md).

`Transaction` (a child Entity) has no Repository of its own — it is looked up within the Account Aggregate's boundary via `AccountRepository.find_transactions()`. This precisely follows the root principle that "a child Entity inside an Aggregate is saved/looked up together, only through the Aggregate Root's Repository."

---

## Repository implementation — `infrastructure/persistence/account_repository.py`

```python
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_account(self, account: Account) -> None:
        existing = await self._session.get(AccountModel, account.account_id)
        if existing:
            existing.amount = account.balance.amount
            existing.status = account.status.value
            existing.updated_at = datetime.utcnow()
        else:
            self._session.add(AccountModel(id=account.account_id, ...))

        for transaction in account.pull_pending_transactions():
            self._session.add(TransactionModel(...))

        await self._session.flush()
```

That `save_account()` behaves like an upsert by distinguishing new vs. existing precisely matches the root principle "don't add a separate update method to the Repository — save with a single `save<Noun>`." Saving the child Entities (`Transaction`) the Aggregate created together via `pull_pending_transactions()` is also a cascade save that respects the Aggregate boundary.

---

## Dynamic filter pattern

```python
# infrastructure/persistence/account_repository.py
async def find_accounts(self, page: int, take: int, account_id=None, owner_id=None, status=None):
    stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
    count_stmt = select(func.count()).select_from(AccountModel).where(AccountModel.deleted_at.is_(None))

    if account_id:
        stmt = stmt.where(AccountModel.id == account_id)
        count_stmt = count_stmt.where(AccountModel.id == account_id)
    if owner_id:
        stmt = stmt.where(AccountModel.owner_id == owner_id)
        count_stmt = count_stmt.where(AccountModel.owner_id == owner_id)
    if status:
        stmt = stmt.where(AccountModel.status.in_(status))
        count_stmt = count_stmt.where(AccountModel.status.in_(status))
    ...
```

Each condition is applied only when a value is present (`if account_id:`), and `count_stmt` applies the same conditions in parallel to compute an accurate total count — this matches the principle required by [api-response.md](api-response.md), "count is the total after filters are applied."

---

## Principles

- **1 Aggregate Root = 1 implementation; write/read ABCs may be split**: a single `SqlAlchemyAccountRepository` implementation satisfies both the write interface `AccountRepository` and the read-only interface `AccountQuery` — see [cqrs-pattern.md](cqrs-pattern.md).
- **The interface lives in domain/, the implementation in infrastructure/**.
- **Saving uses only a single `save<Noun>`, with no separate update method**: follows the root's `save<Noun>` naming as-is — `save_account`/`save_card`/`save_payment`/`save_refund`. Distinguishing new vs. existing is handled as an upsert inside the method.
- **Lookup methods are unified under a single `find<Noun>s`**: a single `AccountQuery.find_accounts()` handles both list and single-item lookups — a single-item lookup is done by calling it with the `account_id`+`owner_id` filters and `take=1`, then pulling out the first item. Anti-patterns such as `find_by_*`/`find_all`/`count*`/bare `save`/bare `delete`/`update_*` are caught by the harness's `repository-naming` rule.
- **No separate `update_*` methods are added**: a state change is expressed by loading the Aggregate with `find_<noun>s`, mutating it via a domain method, then saving it whole again with `save_<noun>` — a method for partial updates (e.g. `update_status`) is never added separately to the Repository ABC.
- **Dynamic filters are applied only when a value is present**.
- **Deletion is a soft delete**: see [persistence.md](persistence.md).

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root design details
- [layer-architecture.md](layer-architecture.md) — layer dependency direction, DI binding
- [domain-events.md](domain-events.md) — saving Domain Events → Outbox in the Repository
- [persistence.md](persistence.md) — transactions, soft delete, migrations
- [api-response.md](api-response.md) — list-query responses and count
