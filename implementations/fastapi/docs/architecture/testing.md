# Testing Strategy

> Framework-agnostic principles: [../../../../docs/architecture/testing.md](../../../../docs/architecture/testing.md)

The root requires 3 layers (Domain unit, Application unit, E2E).

| Layer | Verification scope | Dependency strategy | Current state in this repository |
|--------|----------|------------|----------------------|
| Domain unit tests | Aggregate, Value Object | No framework | `tests/unit/domain/` |
| Application unit tests | The Handler's orchestration logic | Mocks the Repository/Service | `tests/unit/application/` |
| E2E tests | The full Interface→Application→Infrastructure path | A real DB (testcontainers) | `tests/test_account_e2e.py`, `tests/test_notification_e2e.py` |

## Current implementation — Domain/Application unit tests

`tests/unit/domain/test_account.py`, `tests/unit/domain/test_money.py`, `tests/unit/application/test_create_account_handler.py`, `tests/unit/application/test_deposit_handler.py` exist, and they follow exactly the pattern the two sections below show (Domain is instantiated directly with no mocks, Application mocks the Repository/Outbox with `AsyncMock`).

`tests/conftest.py` fills in a dummy value via `os.environ.setdefault("DATABASE_URL", ...)` if `DATABASE_URL` isn't set, so that `validate_env()` from [config.md](config.md) passes at the point `main.py` gets imported during test runs.

```python
# tests/conftest.py — actual code
import os

# Tests must pass validate_env() at main.py import time — the actual connection target
# is started by each e2e test via testcontainers, then swapped in via dependency_overrides in the fixture.
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/account"
)
```

This `setdefault` doesn't bypass fail-fast itself — it's a test-only device to satisfy the condition that "as long as the test process is running, some value must be present for `validate_env()` to pass" — the actual DB connection is swapped by each E2E test to a testcontainers session via `app.dependency_overrides[get_session]`.

---

## Domain unit tests — verifying `Account` itself with no framework

**Location**: `tests/unit/domain/test_account.py`, `tests/unit/domain/test_money.py`. Below is an excerpt (the actual file includes more cases, such as suspend/reactivate/close).

```python
# tests/unit/domain/test_account.py — actual code (excerpt)
import pytest

from src.account.domain.account import Account
from src.account.domain.account_status import AccountStatus
from src.account.domain.errors import (
    DepositRequiresActiveAccountError,
    InsufficientBalanceError,
    InvalidAmountError,
)
from src.account.domain.events import AccountCreated, MoneyDeposited


def make_active_account(currency: str = "KRW") -> Account:
    return Account.create(owner_id="owner-1", currency=currency, email="owner1@example.com")


def test_create_collects_an_AccountCreated_event_on_account_creation() -> None:
    account = make_active_account()

    events = account.pull_events()

    assert len(events) == 1
    assert isinstance(events[0], AccountCreated)
    assert events[0].owner_id == "owner-1"


def test_deposit_raises_InvalidAmountError_for_a_non_positive_amount() -> None:
    account = make_active_account()
    account.pull_events()   # drain the creation event

    with pytest.raises(InvalidAmountError):
        account.deposit(0)


def test_deposit_raises_an_error_when_depositing_into_a_suspended_account() -> None:
    account = make_active_account()
    account.suspend()

    with pytest.raises(DepositRequiresActiveAccountError):
        account.deposit(1000)


def test_deposit_increases_balance_and_collects_a_MoneyDeposited_event_on_success() -> None:
    account = make_active_account()
    account.pull_events()

    account.deposit(10000)

    assert account.balance.amount == 10000
    events = account.pull_events()
    assert any(isinstance(e, MoneyDeposited) for e in events)


def test_withdraw_raises_InsufficientBalanceError_when_balance_is_insufficient() -> None:
    account = make_active_account()

    with pytest.raises(InsufficientBalanceError):
        account.withdraw(1000)   # withdrawing from an account with zero balance


def test_close_cannot_close_when_balance_is_not_zero() -> None:
    account = make_active_account()
    account.deposit(5000)

    from src.account.domain.errors import AccountBalanceNotZeroError

    with pytest.raises(AccountBalanceNotZeroError):
        account.close()
```

**Principles:**
- An existing factory such as `Account.create()` is reused as a test fixture (no separate mock/stub needed — a Domain object has no framework dependency, so it can be instantiated directly).
- Nothing is mocked — since an Aggregate is a pure object, it's verified directly through a real instance.
- Error verification specifies the exception type via `pytest.raises(SpecificErrorClass)` (comparing a string message is forbidden — consistent with the typing principle in [error-handling.md](error-handling.md)).

Note: the test function names above (`test_create_collects_an_AccountCreated_event_on_account_creation`, etc.) are the actual identifiers in the source file, quoted here for consistency with the code.

---

## Application unit tests — mocking the Repository/Service

**Location**: `tests/unit/application/test_create_account_handler.py`, `tests/unit/application/test_deposit_handler.py`.

Since the Handler's constructor only takes the ABC type (`AccountRepository`) (publishing/receiving from Outbox → SQS is handled independently by `OutboxPoller`/`OutboxConsumer`, and the Command Handler never references them at all — see domain-events.md), a mock object created with `unittest.mock` can be injected as-is.

```python
# tests/unit/application/test_deposit_handler.py — actual code (excerpt)
from unittest.mock import AsyncMock

import pytest

from src.account.application.command.deposit_handler import DepositCommand, DepositHandler
from src.account.domain.account import Account
from src.account.domain.errors import AccountNotFoundError


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()   # automatically mocks every async method of AccountRepository


@pytest.mark.asyncio
async def test_execute_raises_AccountNotFoundError_when_account_is_missing(repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = DepositHandler(repo)

    with pytest.raises(AccountNotFoundError):
        await handler.execute(DepositCommand(account_id="non-existent", requester_id="owner-1", amount=1000))

    repo.save.assert_not_called()


@pytest.mark.asyncio
async def test_execute_calls_save_on_successful_deposit(repo) -> None:
    account = Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")
    account.pull_events()   # drain the creation event — leaves only the deposit event
    repo.find_accounts.return_value = ([account], 1)
    handler = DepositHandler(repo)

    transaction = await handler.execute(
        DepositCommand(account_id=account.account_id, requester_id="owner-1", amount=10000)
    )

    assert transaction.type == "DEPOSIT"
    repo.save.assert_awaited_once_with(account)   # the Aggregate save + Outbox load are handled as one transaction inside save()
```

This test doesn't go so far as to verify that the `MoneyDeposited` event actually results in a notification (a Command Handler's unit test only looks at the orchestration flow up through lookup → domain method call → save) — the notification-sending logic itself, per event type, is either separately unit-tested against `application/event/money_deposited_event_handler.py`, or verified end-to-end by `test_notification_e2e.py` through `OutboxPoller`/`OutboxConsumer` all the way to real LocalStack SES.

**Principles:**
- `unittest.mock.AsyncMock` (standard library, Python 3.8+) automatically mocks `async def` methods — this is sufficient without a separate `pytest-mock`, but once fixture combinations grow, organizing them with `pytest-mock`'s `mocker` fixture can help too.
- The mock follows the ABC's (`AccountRepository`'s) method signature — since `AsyncMock()` mocks every attribute with no spec, use `AsyncMock(spec=AccountRepository)` to enforce the signature and avoid mistakes.
- What's being verified is the **orchestration flow** (lookup → domain method call → save), not business rules — business rules were already verified in the Domain unit tests.

---

## E2E tests

`tests/test_account_e2e.py`, `tests/test_auth_e2e.py` start only a real Postgres via testcontainers to verify the full HTTP endpoint path. `tests/test_card_e2e.py`, `tests/test_payment_e2e.py`, `tests/test_notification_e2e.py` need to verify an Integration Event crossing the three BCs — Account/Card/Payment — or a notification send (SES), so on top of Postgres they also start a `LocalStackContainer` (SQS, and for `test_notification_e2e.py` also SES), and the test fixture itself starts `OutboxPoller`/`OutboxConsumer` directly as background tasks — because `main.py`'s `lifespan` is never triggered in these tests, which run via `httpx.ASGITransport`.

```python
# tests/test_card_e2e.py — actual code (excerpt)
@pytest_asyncio.fixture(scope="session")
async def client() -> AsyncGenerator[AsyncClient, None]:
    with (
        PostgresContainer("postgres:16-alpine") as postgres,
        LocalStackContainer("localstack/localstack:3.0", region_name="us-east-1").with_services("sqs") as localstack,
    ):
        queue_url = create_domain_event_queue(localstack)   # conftest.py — creates the domain-events queue + DLQ
        os.environ["SQS_DOMAIN_EVENT_QUEUE_URL"] = queue_url
        ...
        app.dependency_overrides[get_session] = override_get_session
        outbox_tasks = start_outbox_background_tasks(session_factory)   # conftest.py

        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac

        await stop_outbox_background_tasks(outbox_tasks)
```

Since a Domain Event/Integration Event actually round-trips through SQS as a genuinely async pipeline, the final state can't be `assert`ed right after the API response — the `wait_until(condition_fn, timeout=15.0, interval=0.2)` polling helper in `tests/conftest.py` waits until the condition is satisfied.

```python
# tests/test_card_e2e.py — actual code (excerpt)
@pytest.mark.asyncio
async def test_suspending_account_cascades_to_suspend_its_active_cards(client: AsyncClient) -> None:
    account = await create_account(client, OWNER_ID)
    card = await issue_card(client, OWNER_ID, account["account_id"])

    await client.post(f"/accounts/{account['account_id']}/suspend", headers=auth_headers(OWNER_ID))

    # account.suspended.v1 is processed asynchronously through OutboxPoller → SQS → OutboxConsumer —
    # it may not be reflected immediately right after the response, so this polls.
    await wait_until(lambda: _status_is(client, OWNER_ID, card["card_id"], "SUSPENDED"))
```

**Principles applied:**
- `app.dependency_overrides[get_session]` swaps the real session factory for the testcontainers DB — only the dependency is replaced, with no change to production code.
- `httpx.ASGITransport` calls the ASGI app directly with no real network socket — fast, while still passing through the real HTTP request/middleware path.
- Each test creates and uses its own independent account, so there's no state shared between tests.
- `test_notification_e2e.py` uses a `LocalStackContainer` to confirm an actual SES send — verifying the real send protocol, not a mock.
- **An assertion that verifies genuinely async event processing is polled with `wait_until()`**: asserting immediately can either become flaky depending on timing (occasionally failing) or always pass (coincidentally catching a not-yet-processed state), verifying nothing in practice.

`pytest.ini`'s `asyncio_mode = auto` makes every `async def test_*` automatically subject to `pytest-asyncio` handling.

---

## Test file placement (actual)

```
implementations/fastapi/examples/
  src/
    account/
      domain/
        account.py
  tests/
    conftest.py                       ← sets a DATABASE_URL default (to bypass fail-fast, see the section above)
    unit/
      domain/
        test_account.py               ← Domain unit tests
        test_money.py
      application/
        test_create_account_handler.py ← Application unit tests
        test_deposit_handler.py
    test_account_e2e.py               ← E2E
    test_notification_e2e.py          ← E2E
```

---

## Test naming pattern

```
test_<method>_<condition>_<expected_result>
e.g.: test_withdraw_raises_InsufficientBalanceError_when_balance_is_insufficient
```

Consistent with the naming of the existing E2E tests (`test_deposit_success`, `test_deposit_account_not_found_returns_404`), a fully descriptive `snake_case` condition phrase like the example above is also fine — the whole repository uses English-only identifiers, so when adding a new unit test, just pick whichever of these two English styles (`test_<action>_<condition>_returns_<result>` or a longer descriptive `test_<method>_<condition>_<expected_result>`) reads most clearly for that test.

---

## Principles

- **Have all 3 layers**: Domain unit (fast, no mocks), Application unit (verifies orchestration with mocks), E2E (integration verification with a real DB).
- **Domain tests never mock anything**: since an Aggregate is a pure object, it's instantiated directly.
- **Application tests mock the ABC**: enforce the signature with `AsyncMock(spec=AccountRepository)`.
- **E2E verifies real infrastructure with testcontainers**.
- **Verify errors by type**: `pytest.raises(SpecificError)` — comparing a message string is forbidden.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Domain layer design (the target of unit tests)
- [layer-architecture.md](layer-architecture.md) — Application Handlers (the target of Application unit tests)
- [error-handling.md](error-handling.md) — error-type verification
- [local-dev.md](local-dev.md) — the LocalStack/Postgres images testcontainers uses
