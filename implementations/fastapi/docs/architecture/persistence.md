# Persistence Pattern — Transactions, Common Entity Columns, Soft Delete, Migrations

> Framework-agnostic principles: [../../../../docs/architecture/persistence.md](../../../../docs/architecture/persistence.md)

## Transactions — `AsyncSession` + `get_session()`

This repository uses a single SQLAlchemy `AsyncSession` as the Unit of Work for the entire request. With no separate `TransactionManager`/`contextvars` propagation layer, FastAPI's `Depends` manages the session lifecycle per request.

```python
# src/database.py — actual code
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()   # commits once the request finishes successfully
        except Exception:
            await session.rollback()
            raise
```

Every route in `interface/rest/account_router.py` receives its session through the same `Depends(get_session)`, and assembles both the Repository (`_repo`) and the Technical Service (`_notification_service`) with that session — meaning that within a single HTTP request, the Repository save and the notification-send record (`SentEmailModel`) belong to **the same session, the same transaction**.

```python
# interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)   # reuses the same session
```

Because FastAPI caches `Depends(get_session)` within the same request, both factories actually receive the identical `AsyncSession` instance. The "Unit of Work" concept the root docs require, for bundling writes across multiple Repositories/Services into a single transaction, is satisfied by this caching behavior — instead of using `AsyncLocalStorage`/`contextvars` as in other languages, FastAPI's request-scoped dependency caching plays the same role.

Once the route function returns successfully, the line after `get_session()`'s `yield` (`await session.commit()`) runs. If an exception occurs, the `except` block explicitly rolls back — `async with SessionLocal()` on its own just closes the session on an exception without rolling back, so without this `except` a use case like an inter-account transfer, which saves two different Aggregate instances sequentially within one request, becomes dangerous (if the second save throws, the first save may remain flushed to the session).

---

## Common Entity columns — `created_at`, `updated_at`, `deleted_at`

`AccountModel` (`infrastructure/persistence/account_repository.py`) has all three columns: `created_at`/`updated_at`/`deleted_at`.

```python
class AccountModel(Base):
    __tablename__ = "accounts"
    ...
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
```

`TransactionModel` has only `created_at` — a transaction record is an immutable record that is never modified/deleted after creation, so `updated_at`/`deleted_at` aren't needed. The common-columns principle applies to "an Entity with mutable state" — it isn't a rule mechanically attached to every table.

Once a second or third domain is added, extract this into a shared Mixin.

```python
# src/common/timestamped_mixin.py (proposed)
from datetime import datetime

from sqlalchemy.orm import Mapped, mapped_column


class TimestampedMixin:
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)
    updated_at: Mapped[datetime] = mapped_column(default=datetime.utcnow, onupdate=datetime.utcnow)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True, default=None)
```

---

## Soft Delete

`find_accounts` (`infrastructure/persistence/account_repository.py`) explicitly includes the `AccountModel.deleted_at.is_(None)` condition in its lookup query.

```python
async def find_accounts(self, page: int, take: int, account_id=None, owner_id=None, status=None):
    stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
    if account_id:
        stmt = stmt.where(AccountModel.id == account_id)
    if owner_id:
        stmt = stmt.where(AccountModel.owner_id == owner_id)
    ...
```

That said, `AccountRepository` (ABC) currently has no method corresponding to `delete_account` at all — an account only supports `close()` (transitioning its status to `CLOSED`), and there is no physical/logical deletion use case. If a domain that needs deletion is ever added, add a soft-delete method to the Repository that sets `deleted_at = datetime.utcnow()`, rather than a hard delete (`session.delete(row)`).

```python
# correct approach — soft delete (reference for when one is added)
async def delete_account(self, account_id: str) -> None:
    row = await self._session.get(AccountModel, account_id)
    if row is not None:
        row.deleted_at = datetime.utcnow()
```

---

## Migrations — managed with Alembic

`create_all` **only creates tables that don't exist yet**, and cannot detect adding/changing/removing columns on an existing table — when a schema change is needed in production (adding a column, adding an index, etc.), there's no way to apply it this way. This example adopts Alembic to solve this problem.

```bash
pip install alembic
alembic init -t async migrations   # template for an async engine (create_async_engine)
```

```python
# migrations/env.py — connects target_metadata to the project's Base (also imports the
# outbox/sent_email models so they register on the same Base metadata), and prioritizes
# the DATABASE_URL environment variable over alembic.ini
from src.account.infrastructure.persistence.account_repository import Base
import src.account.infrastructure.notification.sent_email_model  # noqa: F401
import src.outbox.outbox_model  # noqa: F401

database_url = os.getenv("DATABASE_URL")
if database_url:
    config.set_main_option("sqlalchemy.url", database_url)

target_metadata = Base.metadata
```

```bash
# generate a migration after a schema change (auto-detects model changes)
alembic revision --autogenerate -m "add sent_emails table"

# apply migrations
alembic upgrade head

# roll back the last migration
alembic downgrade -1
```

```python
# main.py — create_all removed from lifespan; migrations run in the deployment pipeline
app = FastAPI(title="Account Service")
# no Base.metadata.create_all call — the schema is applied at deploy time via `alembic upgrade head`
```

`create_all` continues to be used in the local development/test environment (a fresh DB every time, where schema validation isn't the goal) — `tests/test_account_e2e.py`/`tests/test_notification_e2e.py` each independently call `create_all` inside their own testcontainers fixtures, without depending on `main.py`'s lifespan. Against an empty DB, `alembic revision --autogenerate` accurately detects all 4 tables, and after applying `alembic upgrade head`, `alembic check` confirms "no additional changes detected."

---

## Principles

- **One request = one `AsyncSession`**: use `Depends(get_session)`'s request-scope caching as the transaction boundary.
- **Every Entity with mutable state gets `created_at`/`updated_at`/`deleted_at`**: an immutable record (`TransactionModel`) is the exception.
- **Deletion is a soft delete**: a `deleted_at` timestamp. Lookups always include `deleted_at IS NULL`.
- **Schema changes are managed via Alembic**: `create_all` is confined to local/test use only.

---

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository interface/implementation separation, method naming
- [domain-events.md](domain-events.md) — the Outbox save also happens in the same session/transaction
- [testing.md](testing.md) — using `create_all` in testcontainers

---

The harness's `soft-delete-filter` rule (`../../harness/rules/soft_delete_filter.py`) fails a SQLAlchemy model under `infrastructure/persistence/` if it has `updated_at` (mutable state) but no `deleted_at`, and verifies that a `find_*` method querying a model with `deleted_at` includes a `deleted_at IS NULL` filter — this rule actually caught `PaymentModel`/`RefundModel` missing `deleted_at`.

The harness's `no-orm-autosync-in-prod-config` rule (`../../harness/rules/no_orm_autosync_in_prod_config.py`) uses AST analysis to check for a `Base.metadata.create_all(...)` call in non-test-only files (app bootstrap paths such as `main.py`, `src/database.py`) — mechanically enforcing the "schema changes are managed via Alembic" principle above.
