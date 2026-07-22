# Aggregate ID Generation

> Framework-agnostic principles: [../../../../docs/architecture/aggregate-id.md](../../../../docs/architecture/aggregate-id.md)

### Principles

- **Where IDs are generated**: the Domain layer (an Aggregate's factory classmethod, e.g. `Account.create()`)
- **Who generates them**: the server. A client-supplied ID is never trusted.
- **Type**: `str`
- **Format**: a 32-character hex string, a UUID v4 with the hyphens removed

```python
"550e8400e29b41d4a716446655440000"    # correct — 32 characters, no hyphens
"550e8400-e29b-41d4-a716-446655440000"  # incorrect — contains hyphens
1, 2, 3                                  # incorrect — auto-increment numbers
```

**Why auto-increment numeric IDs are not used:**
- The number of DB records and the creation order would be exposed externally (security)
- ID collisions become possible across multiple services/shards
- The ID isn't determined until the DB insert, so it can't be generated ahead of time in the Domain layer

---

### `generate_id()`

`Account.create()` in `src/account/domain/account.py`, `Transaction.create()` in `src/account/domain/transaction.py`, and every sent_email ID issued by `notification_service.py` all use `generate_id()` below (32-character hex with hyphens removed, `uuid.uuid4().hex`). `outbox_writer.py` uses `uuid.uuid4().hex` directly to keep the same format.

---

### ID generation utility

Extracted as a project-wide common utility that every domain reuses.

```python
# src/common/generate_id.py
import uuid


def generate_id() -> str:
    return uuid.uuid4().hex
```

---

### Usage in the Aggregate

```python
# src/account/domain/account.py
from __future__ import annotations
from datetime import datetime

from src.common.generate_id import generate_id
from .money import Money
from .account_status import AccountStatus


class Account:
    @classmethod
    def create(cls, owner_id: str, currency: str, email: str) -> Account:
        now = datetime.utcnow()
        account = cls(
            account_id=generate_id(),          # correct — 32-character hex, no hyphens
            owner_id=owner_id,
            email=email,
            balance=Money(0, currency),
            status=AccountStatus.ACTIVE,
            created_at=now,
            updated_at=now,
        )
        ...
        return account
```

The same approach applies to `transaction_id` in `Transaction.create()` (`src/account/domain/transaction.py`).

- **New creation**: automatically assigned via `generate_id()` inside the factory classmethod (`Account.create`, `Transaction.create`).
- **Restoring from the DB**: `SqlAlchemyAccountRepository._to_domain()` (`src/account/infrastructure/persistence/account_repository.py`) passes the stored `account_id` straight into `Account.__init__` — it never creates a new ID.

---

### ID handling in the Repository implementation

The Repository uses the ID the Aggregate already has, as-is. The DB never issues a new ID.

```python
# src/account/infrastructure/persistence/account_repository.py
async def save(self, account: Account) -> None:
    existing = await self._session.get(AccountModel, account.account_id)
    if existing:
        existing.amount = account.balance.amount
        existing.status = account.status.value
    else:
        self._session.add(AccountModel(
            id=account.account_id,   # uses the ID the Aggregate already has, as-is
            owner_id=account.owner_id,
            ...
        ))
```

---

### Column type — declared with a fixed length in the DB too

Since it's a fixed 32-character string, declaring it as `CHAR(32)` is more accurate than a variable-length `VARCHAR`. `AccountModel.id` is currently declared as `Mapped[str]` (defaulting to `VARCHAR`); once the hyphen-removal rule is applied, the fixed length can be made explicit as follows.

```python
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import CHAR

id: Mapped[str] = mapped_column(CHAR(32), primary_key=True)
```

---

### Child Entity/object IDs

`Transaction` inside the Aggregate (which is a child Entity) likewise uses a `generate_id()`-based 32-character hex string. A Value Object (`Money`) has no identity, so the ID rule doesn't apply to it.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — distinguishing Aggregate/Entity/Value Object
- [repository-pattern.md](repository-pattern.md) — saving an Aggregate in the Repository

---

The harness's `aggregate-id-format` rule (`../../harness/rules/aggregate_id_format.py`) mechanically verifies that `src/common/generate_id.py` uses `uuid.uuid4().hex` and does not contain the `str(uuid.uuid4())` (hyphen-included) anti-pattern.
