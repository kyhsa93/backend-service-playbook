# Tactical Design — Aggregate, Entity, Value Object, Domain Event

> Framework-agnostic principles: [../../../../docs/architecture/tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md)

This repository models Python objects in three different ways — each directly reflecting the difference in nature between an Aggregate Root, an Entity, and a Value Object.

| Concept | How this repository implements it | Why |
|------|----------------------|------|
| Aggregate Root | A plain class (`__init__` + methods) | Its internal state changes over time, and changes must only be allowed through methods — a `@dataclass`'s auto-generated `__init__` lets fields be freely overwritten, which doesn't suit protecting invariants |
| Entity (child object) | `@dataclass(frozen=True)` | A child record whose value never changes after creation — its equality is determined by its identifier (`transaction_id`) |
| Value Object | `@dataclass(frozen=True)` | Immutable, no identifier, equality determined by the combination of attributes |
| Domain Event | `@dataclass(frozen=True)` | An immutable record of a fact that happened in the past |

---

## Aggregate Root — `Account`

`Account` in `src/account/domain/account.py` is a plain class that encapsulates business rules and invariants. It doesn't enforce that an attribute can't be changed directly from the outside like `account.balance = ...` (Python has no true private), but **every state change is designed to happen only through a domain method** (`deposit`, `withdraw`, `suspend`, `reactivate`, `close`), and the caller (an Application Handler) only ever uses these methods.

Not having a writable property that can be directly assigned from outside via a `@property`+`@x.setter` pair is also part of this convention — the harness's `aggregate-no-public-setters` rule checks via AST whether a `domain/` class has a public `@x.setter` (since Python has no true access control, this rule narrowly targets only the clearly identifiable `@x.setter` decorator).

```python
# src/account/domain/account.py
class Account:
    def __init__(
        self, account_id: str, owner_id: str, email: str, balance: Money,
        status: AccountStatus, created_at: datetime, updated_at: datetime,
    ) -> None:
        self.account_id = account_id
        self.owner_id = owner_id
        self.email = email
        self.balance = balance
        self.status = status
        self.created_at = created_at
        self.updated_at = updated_at
        self._events: list[AccountDomainEvent] = []
        self._pending_transactions: list[Transaction] = []

    @classmethod
    def create(cls, owner_id: str, currency: str, email: str) -> Account:
        now = datetime.utcnow()
        account = cls(account_id=str(uuid.uuid4()), owner_id=owner_id, email=email,
                       balance=Money(0, currency), status=AccountStatus.ACTIVE, created_at=now, updated_at=now)
        account._events.append(AccountCreated(...))
        return account

    def withdraw(self, amount: int) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise WithdrawRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        if self.balance.is_less_than(money):
            raise InsufficientBalanceError()
        self.balance = self.balance.subtract(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "WITHDRAWAL", money)
        self._pending_transactions.append(transaction)
        self._events.append(MoneyWithdrawn(...))
        return transaction
```

**How the core principles are upheld:**
- **Invariants are validated immediately upon entering a domain method**: withdrawing from a suspended account (`WithdrawRequiresActiveAccountError`), insufficient balance (`InsufficientBalanceError`), and a negative/zero amount (`InvalidAmountError`) are all validated before the state changes, throwing an exception immediately on failure.
- **Created via a factory classmethod**: `Account.create()` is the sole creation path, and it records an `AccountCreated` event at the moment of creation. `Account.__init__` is the low-level constructor (used by the Repository when restoring a DB row).
- **Other Aggregates are referenced only by ID**: whether it's another Aggregate in the same BC (`Payment` in `src/payment/domain/payment.py` and `Refund` in `refund.py`), or an Aggregate in a different BC (e.g. `card` referencing `payment`'s Aggregate), they reference each other only by an ID such as `payment_id: str`, never by object reference — the root [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md) principle "other Aggregates are referenced only by ID (object references are forbidden)." Within the same BC, this is checked by the harness's `no-cross-aggregate-reference` rule (targeting `src/payment/domain/{payment.py,refund.py}`, see the "Domain Service" section of [layer-architecture.md](layer-architecture.md)); across BCs, it's checked by the `no-cross-bc-domain-import` rule (`../../harness/rules/no_cross_bc_domain_import.py`, which fails if `src/<bc>/domain/*.py` directly imports another BC's `domain/`).

---

## Entity (child object) — `Transaction`

`Transaction` in `src/account/domain/transaction.py` is a child object belonging to the `Account` Aggregate. It's implemented as a `frozen=True` dataclass, but since it has a unique identifier, `transaction_id`, it's an Entity rather than a Value Object — it's closer to an "immutable Entity" in that its value never changes after creation (this modeling is appropriate since a deposit/withdrawal record is a fact that is never modified once created).

```python
# src/account/domain/transaction.py
@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    account_id: str
    type: TransactionType
    amount: Money
    created_at: datetime

    @classmethod
    def create(cls, account_id: str, type: TransactionType, amount: Money) -> Transaction:
        return cls(transaction_id=str(uuid.uuid4()), account_id=account_id, type=type,
                    amount=amount, created_at=datetime.utcnow())
```

`Account` pulls out newly created `Transaction`s via `pull_pending_transactions()` and hands them to the Repository — upholding the principle that an Entity is saved/looked up only through its Aggregate Root.

---

## Value Object — `Money`

`Money` in `src/account/domain/money.py` has no identifier, and is an immutable object whose equality is determined only by the combination of `amount` and `currency`.

```python
# src/account/domain/money.py
@dataclass(frozen=True)
class Money:
    amount: int
    currency: str

    def __post_init__(self) -> None:
        if self.amount < 0:
            raise InvalidMoneyAmountError()

    def add(self, other: Money) -> Money:
        self._assert_same_currency(other)
        return Money(self.amount + other.amount, self.currency)

    def is_zero(self) -> bool:
        return self.amount == 0
```

`@dataclass(frozen=True)` is the idiomatic way to express an immutable Value Object in Python — reassigning a field raises `FrozenInstanceError`, so a state change after creation is structurally impossible. `add()`/`subtract()` don't mutate the existing instance; they return a new `Money`. `__post_init__` validates the invariant (the amount can't be negative) at creation time — a Value Object's invariant only needs to be validated once, in the constructor, since there is no path to change it afterward, so it holds permanently.

**Criteria for using a Value Object**: when meaning can be expressed by attributes alone and an identifier is unnecessary. `Money` (amount + currency), and future additions such as `Address`, `PhoneNumber`, are candidates.

---

## Domain Event — `events.py`

Every event in `src/account/domain/events.py` follows the `@dataclass(frozen=True)` + past-tense naming convention (`AccountCreated`, `MoneyDeposited`, `AccountSuspended`, etc.).

```python
@dataclass(frozen=True)
class MoneyDeposited:
    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime
```

Domain Events are grouped into a `Union` type that forms `pull_events()`'s return type.

```python
# account.py
AccountDomainEvent = Union[
    AccountCreated, MoneyDeposited, MoneyWithdrawn, AccountSuspended, AccountReactivated, AccountClosed
]
```

→ See [domain-events.md](domain-events.md) for details of the collection/storage/publishing flow.

---

## Aggregate boundary criteria — why `Account` and `Transaction` are grouped as one

- **They're created together and share a lifecycle**: `Transaction` cannot exist without a call to `Account.deposit()`/`withdraw()`.
- **Their invariants are entangled**: whether a withdrawal is allowed (`balance.is_less_than()`) can only be decided by looking at `Account`'s current balance, and that decision must happen atomically with creating the `Transaction`.
- **Reference frequency**: a `Transaction` list is always looked up only within the context of a specific `Account` (`find_transactions(account_id, ...)`) — there's no reason to split it into a separately queried Aggregate.

Conversely, the reason `Account` and its `owner` (the account holder) aren't grouped into the same Aggregate is that a change to the owner's information doesn't affect the account's balance/status invariants — it holds only an ID reference, `owner_id`.

---

## Design principles summary

| Principle | Application in this repository |
|---|---|
| Business rules live in the Aggregate | Encapsulated in `Account.deposit/withdraw/suspend/reactivate/close` |
| Transaction boundary = Aggregate boundary | A single `save()` call commits `Account` + its child `Transaction`s together |
| Error messages are typed | Exception classes in `domain/errors.py` (distinguished by class rather than a free-form string) — [error-handling.md](error-handling.md) |
| Domain is framework-agnostic | No `fastapi`/`sqlalchemy`/`aioboto3` imports |
| Mutable state → plain class, immutable value → `frozen dataclass` | `Account` vs. `Transaction`/`Money`/events |

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer responsibilities, dependency direction
- [repository-pattern.md](repository-pattern.md) — the per-Aggregate Repository
- [domain-events.md](domain-events.md) — Domain Event publishing/receiving, the Outbox
- [aggregate-id.md](aggregate-id.md) — the ID generation rule
