# Layer Architecture

> Framework-agnostic principles: [../../../../docs/architecture/layer-architecture.md](../../../../docs/architecture/layer-architecture.md)

## Dependency direction

```
Interface (APIRouter)  →  Application (Handler)  →  Domain (Aggregate, Repository ABC)
                                                          ↑
                                                   Infrastructure (Repository implementation, Technical Service implementation)
```

- An upper layer may depend on a lower layer, but a lower layer never depends on an upper layer.
- The Domain layer depends on no layer at all — no `fastapi`, `sqlalchemy`, `aioboto3` imports.
- The Infrastructure layer implements the ABCs of Domain/Application (dependency inversion) — since FastAPI has no dedicated DI container, `Depends`'s factory functions handle this binding.

Whether `domain/` (of the same domain or another) imports `application/`/`infrastructure/`/`interface/` is checked via AST by the harness's `domain-layer-isolation` rule — a broader, path-based structural check than `domain-purity` (the framework-name blocklist).

Let's look at each layer using this repository's actual code (`examples/src/account/`).

---

## The Domain layer — `domain/`

The core of the business rules. Depends on no framework.

1. **Aggregate Root** — `Account` in `account.py`. Implemented as a plain class (`__init__` + methods) — every state change (`deposit`, `withdraw`, `suspend`, `reactivate`, `close`) happens only through an Aggregate method.
2. **Entity** — `Transaction` in `transaction.py`. A `@dataclass(frozen=True)`, but it's an Entity since its equality is determined by the unique identifier `transaction_id`.
3. **Value Object** — `Money` in `money.py`. `@dataclass(frozen=True)`, no identifier, equality determined by the combination of attributes.
4. **Domain Event** — `AccountCreated`, `MoneyDeposited`, etc. in `events.py`. All `@dataclass(frozen=True)`, past-tense names.
5. **Repository interface** — `AccountRepository(ABC)` in `repository.py`. Implemented in `infrastructure/`.

```python
# domain/repository.py — the Repository interface (ABC)
from abc import ABC, abstractmethod

from .account import Account
from .transaction import Transaction


class AccountRepository(ABC):
    @abstractmethod
    async def find_accounts(
        self, page: int, take: int,
        account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
    ) -> tuple[list[Account], int]: ...

    @abstractmethod
    async def save(self, account: Account) -> None: ...
```

→ See [tactical-ddd.md](tactical-ddd.md) for detailed Aggregate/Entity/Value Object design.

---

## The Application layer — `application/`

The use case's **orchestrator**. It never performs business logic directly, delegating to the Aggregate. Split into `command/` (writes) and `query/` (reads).

```python
# application/command/deposit_handler.py
class DepositHandler:
    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: DepositCommand) -> Transaction:
        accounts, _ = await self._repo.find_accounts(page=0, take=1, account_id=cmd.account_id, owner_id=cmd.requester_id)
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(cmd.account_id)
        transaction = account.deposit(cmd.amount)   # business logic is delegated to the Aggregate
        await self._repo.save(account)               # Aggregate save + Outbox load, one transaction
        return transaction   # returns immediately after saving — publishing/receiving Outbox → SQS is OutboxPoller/OutboxConsumer's job (domain-events.md)
```

The Handler's constructor takes the ABC (`AccountRepository`) as its type, not a concrete class — `OutboxPoller`/`OutboxConsumer`, which handle publishing/receiving from Outbox → SQS, run independently and periodically with no relation to the Command Handler at all, so they never appear in its constructor. Thanks to this, the Application unit tests covered in [testing.md](testing.md) are possible with mocks alone, with no real DB/SES. `NotificationService` isn't depended on by the Command Handler — it's depended on by `application/event/<event>_event_handler.py`, which `OutboxConsumer` calls upon receiving from SQS (see [domain-events.md](domain-events.md)).

→ See [cqrs-pattern.md](cqrs-pattern.md) for Command/Query Handler details.

### Technical Service interfaces — `application/service/`

For a concern whose core is a technical implementation (sending email, file storage, Secrets Manager, etc.), the ABC lives in Application and the implementation in Infrastructure. Examples in this repository:

```python
# application/service/notification_service.py — the interface
class NotificationService(ABC):
    @abstractmethod
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None: ...
```

```python
# infrastructure/notification/notification_service.py — the implementation (SES)
class SesNotificationService(NotificationService):
    async def notify(self, event: AccountDomainEvent, outbox_event_id: str) -> None:
        try:
            await self._send_and_record(event, outbox_event_id)  # first checks for a duplicate send via outbox_event_id
        except Exception:
            logger.exception("Failed to send notification email: ...")
```

**Why this is separated:**
- `DepositHandler` never depends directly on `aioboto3` or the SES API.
- Even if the send channel changes from SES → SNS/Slack etc., only the implementation needs to be swapped.
- In tests, `NotificationService` can be replaced with a mock object, verifying only the Handler logic with no actual email sent.

When adding a second technical concern (file storage, Secrets Manager), follow the same structure (an ABC at `application/service/<concern>_service.py` + an implementation at `infrastructure/<concern>/<provider>_<concern>_service.py`) — see [file-storage.md](file-storage.md), [secret-manager.md](secret-manager.md).

---

### Domain Service — pure domain logic coordinating multiple Aggregates

Since Account/Card are each a single-Aggregate BC, they couldn't demonstrate "a rule that can't be decided by a single Aggregate alone."
`examples/src/payment/` (the Payment BC) is the first domain with two Aggregates, `Payment`/`Refund`, so this pattern
can be confirmed with actually working code.

**Domain rule**: "A refund requires the original payment to be in the COMPLETED state, and the refund amount cannot exceed the payment amount."
`Payment` doesn't know about a refund attempt (`Refund`) against it (a refund only exists as a separate Aggregate), and `Refund`
doesn't know the original payment's amount/status (it only references it via `payment_id`). Putting this decision into either
Aggregate's method would require taking the other Aggregate entirely as a parameter, breaking the boundary, so it lives in a
separate Domain Service that the Application layer — having loaded both Aggregates — delegates to:

```python
# domain/refund_eligibility_service.py — a Domain Service. Never registered in FastAPI's Depends
# (or any other DI container) — it's stateless, pure decision logic, so the Application
# layer instantiates it directly whenever needed.
@dataclass(frozen=True)
class RefundDecision:
    approved: bool
    reason: str | None = None


class RefundEligibilityService:
    def evaluate(self, payment: Payment, refund: Refund) -> RefundDecision:
        if payment.status != PaymentStatus.COMPLETED:
            return RefundDecision(approved=False, reason="A refund can only be requested for a completed payment.")
        if refund.amount > payment.amount:
            return RefundDecision(approved=False, reason="The refund amount cannot exceed the payment amount.")
        return RefundDecision(approved=True)
```

```python
# application/command/request_refund_handler.py — loads both Repositories and delegates
class RequestRefundHandler:
    def __init__(self, payment_repo: PaymentRepository, refund_repo: RefundRepository) -> None:
        self._payment_repo = payment_repo
        self._refund_repo = refund_repo
        self._refund_eligibility_service = RefundEligibilityService()

    async def execute(self, cmd: RequestRefundCommand) -> Refund:
        payments, _ = await self._payment_repo.find_payments(page=0, take=1, payment_id=cmd.payment_id, owner_id=cmd.requester_id)
        payment = payments[0] if payments else None
        if payment is None:
            raise PaymentNotFoundError(cmd.payment_id)

        refund = Refund.create(payment_id=payment.payment_id, amount=cmd.amount, reason=cmd.reason)

        decision = self._refund_eligibility_service.evaluate(payment, refund)
        if decision.approved:
            refund.approve(account_id=payment.account_id, owner_id=payment.owner_id)
        else:
            # A refund rejection is a valid domain decision outcome — instead of throwing
            # an exception, the Refund saved as REJECTED is returned as-is. The Interface
            # layer responds with 201 + a status field for this (not a 4xx error).
            refund.reject(decision.reason or "The refund request has been rejected.")

        await self._refund_repo.save(refund)
        return refund
```

`RefundEligibilityService` performs only a purely functional decision — it never calls a Repository directly (doing so would be
the "misusing a Domain Service" anti-pattern; see "Anti-patterns when using a Domain Service" in root [domain-service.md](../../../../docs/architecture/domain-service.md)). Loading is always the Application layer's (`RequestRefundHandler`'s) job.

The unit test also instantiates `RefundEligibilityService` directly without going through the Application layer, verifying only
the decision logic (`tests/unit/domain/test_refund_eligibility_service.py`).

Full code: `examples/src/payment/domain/refund_eligibility_service.py`, `payment.py`, `refund.py`,
`examples/src/payment/application/command/request_refund_handler.py`.

Whether `Payment` references the `Refund` class (or vice versa) directly as a field/constructor-parameter type, rather than
only via an ID reference such as `payment_id: str`, is checked by the harness's `no-cross-aggregate-reference` rule, targeting
`src/payment/domain/{payment.py,refund.py}`.

---

## The Infrastructure layer — `infrastructure/`

The only layer that actually accesses external systems.

```python
# infrastructure/persistence/account_repository.py
class SqlAlchemyAccountRepository(AccountRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_accounts(self, page: int, take: int, account_id=None, owner_id=None, status=None):
        stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))
        if account_id:
            stmt = stmt.where(AccountModel.id == account_id)
        if owner_id:
            stmt = stmt.where(AccountModel.owner_id == owner_id)
        # ... (see repository-pattern.md for the full-count count_stmt, applying offset/limit)
        rows = (await self._session.execute(stmt.offset(page * take).limit(take))).scalars().all()
        return [self._to_domain(row) for row in rows], total
```

The ABC implementations of Domain/Application (`SqlAlchemyAccountRepository`, `SesNotificationService`) all live here. Since FastAPI has no dedicated DI container, the `Depends` factory functions in `interface/rest/account_router.py` serve as the "binding point":

```python
# interface/rest/account_router.py
def _repo(session: AsyncSession = Depends(get_session)) -> SqlAlchemyAccountRepository:
    return SqlAlchemyAccountRepository(session)   # AccountRepository(ABC) ← SqlAlchemyAccountRepository(implementation)


def _notification_service(session: AsyncSession = Depends(get_session)) -> NotificationService:
    return SesNotificationService(session)        # NotificationService(ABC) ← SesNotificationService(implementation)
```

A route function's parameter type is declared as the ABC (`AccountRepository`), but what's actually injected is the implementation the factory returns. `NotificationService` is never received by any route at all — it's only assembled inside `build_event_handlers()` in `src/outbox/event_handlers.py`, and passed to an event handler when `OutboxConsumer` processes an SQS message (see [domain-events.md](domain-events.md)).

---

## The Interface layer — `interface/rest/`

The entry point for external requests (HTTP).

1. Receives the request (`account_router.py`)
2. Constructs the Handler and calls `execute()`
3. Errors are caught by `main.py`'s `@app.exception_handler` and converted into an HTTP response (the Router itself never catches them)

```python
# interface/rest/account_router.py — actual code
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

`current_user` is the user info that passed JWT verification (see [authentication.md](authentication.md)) — it's not a header value the client can set arbitrarily.

### Interface DTOs perform only thin conversion

The Pydantic models in `interface/rest/schemas.py` (`DepositRequest`, `TransactionResponse`) only define the HTTP request/response shape, with no logic beyond validation. They only wrap Application's Command/Result as-is.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object, Domain Event details
- [repository-pattern.md](repository-pattern.md) — Repository pattern details
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Handler details
- [domain-events.md](domain-events.md) — Domain Event, the Outbox pattern
- [directory-structure.md](directory-structure.md) — the full directory tree
- root [domain-service.md](../../../../docs/architecture/domain-service.md) — the framework-agnostic principles of the Domain Service/Technical Service pattern (this document's "Domain Service" section's `RefundEligibilityService` is the actual-code evidence for it)
