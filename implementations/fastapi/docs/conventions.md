# Coding Conventions

## 1. File naming rules

- Every file name: `snake_case.py`
- Package (directory) names: lowercase (`domain`, `application`, `persistence`, `notification`)
- Command/Query Handler: `<verb>_<noun>_handler.py` (placed in `application/command/` or `application/query/`) — the `<Verb><Noun>Command`/`<Verb><Noun>Query` dataclass and the `<Verb><Noun>Handler` class live together in the same file
- Query Result: `result.py` (placed in `application/query/`; multiple Result dataclasses can be grouped in one file)
- Adapter interface: `<external_domain>_adapter.py` (placed in `application/adapter/`)
- Adapter implementation: `<provider>_<external_domain>_adapter.py` or `in_process_<external_domain>_adapter.py` (placed in `infrastructure/`) — prefixed with the implementation technology
- Technical Service interface: `<concern>_service.py` (placed in `application/service/`) — `notification_service.py`, `storage_service.py`, `secret_service.py`, etc.
- Technical Service implementation: `<provider>_<concern>_service.py` (placed in `infrastructure/<concern>/`) — `ses_notification_service.py`, `s3_storage_service.py`
- Repository interface: `repository.py` or `<aggregate>_repository.py` (placed in `domain/`)
- Repository implementation: `<aggregate>_repository.py` (placed in `infrastructure/persistence/`; the implementation technology is shown as a `SqlAlchemy` prefix on the class name, with the file name itself left as-is)
- Aggregate Root: `<aggregate_root>.py` (the domain layer)
- Entity (a child object): `<entity>.py` (the domain layer)
- Value Object: `<value_object>.py` (the domain layer)
- Domain Event: `events.py` (the domain layer; events are grouped into one file per domain)
- Domain exceptions: `errors.py` (the domain layer)
- Error codes: `error_codes.py` (the domain layer, mapped 1:1 with messages)
- A domain enum: split out by unit of meaning into files such as `<concern>_status.py` (e.g. `account_status.py`, `order_status.py`) — placed in `domain/`, not a module root (since a Python file is itself already a namespace, there's no need to gather them into a module root the way NestJS does)
- Constants: `constants.py` (the domain package root, created only when needed)
- Router: `<domain>_router.py` (placed in `interface/rest/`)
- Interface DTOs (Pydantic): `schemas.py` (placed in `interface/rest/`; request/response models are grouped in one file)
- The ID-generation util: `generate_id.py` (placed in `src/common/`, shared across domains)
- Configuration classes: `<concern>_config.py` (placed in `src/config/`) — `database_config.py`, `jwt_config.py`
- Configuration validation: `validator.py` (placed in `src/config/`)

---

## 2. Class naming rules

- Aggregate Root: `Order`, `Account` (a domain noun, `PascalCase`)
- Entity (a child object): `OrderItem`, `Transaction`
- Value Object: `Money`, `Address`
- Domain Event: `OrderPlaced`, `OrderCancelled` (past tense)
- Repository interface: `OrderRepository`, `AccountRepository` (never includes the implementation technology's name)
- Repository implementation: `SqlAlchemy<Aggregate>Repository` — `SqlAlchemyOrderRepository`
- Command: `<Verb><Noun>Command` — `CancelOrderCommand`, `CreateOrderCommand`
- CommandHandler: `<Verb><Noun>Handler` — `CancelOrderHandler`, `CreateOrderHandler`
- Query: `<Verb><Noun>Query` — `GetOrderQuery`, `GetOrdersQuery`
- QueryHandler: `<Verb><Noun>Handler` — `GetOrderHandler`, `GetOrdersHandler`
- Result: `<Verb><Noun>Result` / `<Noun>Result` — `GetOrderResult`, `GetOrdersResult`, `OrderSummaryResult`
- Adapter interface: `<ExternalDomain>Adapter` — `PaymentAdapter`
- Adapter implementation: `<Provider><ExternalDomain>Adapter` — `InProcessPaymentAdapter`, `HttpPaymentAdapter`
- Technical Service interface: `<Concern>Service` — `NotificationService`, `StorageService`, `SecretService` (never includes the implementation technology's name)
- Technical Service implementation: `<Provider><Concern>Service` — `SesNotificationService`, `S3StorageService`, `AwsSecretService`
- Domain exceptions: `PascalCase` + `Error` — `OrderNotFoundError`, `OrderAlreadyCancelledError`
- The error-code enum: `<Domain>ErrorCode` (extends `str, Enum`, with values as fixed `SCREAMING_SNAKE_CASE` strings) — `OrderErrorCode`
- Pydantic request/response models: `<Verb><Noun>Request` / `<Verb><Noun>Response` — `CreateOrderRequest`, `GetOrderResponse`
- Functions/variables: `snake_case` — `create_order`, `pull_events`
- Constants: `UPPER_SNAKE_CASE` — `MAX_ORDER_AMOUNT`

---

## 3. Enum / constant placement rules

- Since a Python file is itself a namespace, the NestJS-style rule "gather them into a single `<domain>-enum.ts` at the module root" doesn't directly apply — instead, **split them by unit of meaning and place them in `domain/`**.
- An enum that's part of an Aggregate, such as a domain status value, is defined in `domain/<concern>_status.py`.
- A constant shared by multiple aggregates is gathered into `constants.py` at the domain package root. A constant confined to a single domain can live next to the file that uses it — just avoid an inline magic number/string.

```python
# src/order/domain/order_status.py
from enum import Enum


class OrderStatus(str, Enum):
    PENDING = "pending"
    PAID = "paid"
    CANCELLED = "cancelled"
```

```python
# src/order/constants.py
MAX_ORDER_AMOUNT = 9_999_999
ALLOWED_ORDER_STATUSES = [OrderStatus.PENDING, OrderStatus.PAID]
```

- An enum used by the Application layer (Command/Query/Result) is also imported and reused from `domain/` — never redefine an enum separately in Application.

---

## 4. Typing pattern

### The Aggregate Root — a plain class; a dataclass's auto-generated constructor is forbidden

The Aggregate Root, whose state changes and which must guard its own invariants, is written as a plain class with `__init__` + domain methods. A `@dataclass`'s auto-generated `__init__` lets fields be freely overwritten, which doesn't suit protecting invariants.

```python
# correct approach — a plain class
class Order:
    def __init__(self, order_id: str, user_id: str, items: list[OrderItem], status: OrderStatus) -> None:
        if not items:
            raise OrderMustHaveAtLeastOneItemError()
        self.order_id = order_id
        self.user_id = user_id
        self.items = items
        self.status = status
        self._events: list[OrderDomainEvent] = []
```

```python
# incorrect approach — declaring the Aggregate Root as a dataclass
@dataclass
class Order:
    order_id: str
    items: list[OrderItem]
    status: OrderStatus   # can be arbitrarily overwritten from outside, e.g. order.status = "cancelled"
```

### Entity / Value Object / Domain Event — `@dataclass(frozen=True)`

A child object, value object, or event whose value never changes after creation is declared as a `frozen=True` dataclass. Reassigning a field raises `FrozenInstanceError`, so immutability is structurally guaranteed.

```python
@dataclass(frozen=True)
class OrderItem:
    item_id: str
    name: str
    price: int
    quantity: int

    def __post_init__(self) -> None:
        if self.price <= 0:
            raise InvalidPriceError()
        if self.quantity <= 0:
            raise InvalidQuantityError()
```

A Value Object that needs equality comparison just uses the `__eq__` (attribute-based equality) a `frozen=True` dataclass auto-generates — never create a separate `equals()` method.

### Command / Query — `@dataclass`, with fields declared via type hints

```python
@dataclass
class CancelOrderCommand:
    order_id: str
    reason: str
    refund_amount: int | None = None
```

- Since a Command/Query is freshly created on every request (unlike a Handler's constructor injection), `frozen=True` isn't mandatory, but attaching `frozen=True` is recommended to avoid accidentally mutating a field.
- If a field needs a default value, use a dataclass field default — the counterpart to NestJS's `Object.assign(this, command)` constructor pattern is a dataclass's auto-generated `__init__`.

### Literal/enum types — used for domain state values

```python
# correct approach — an enum or Literal
status: OrderStatus
result: Literal["success", "fail"]

# incorrect approach — allowing an arbitrary string
status: str
```

### The timezone rule — store in UTC, use timezone-aware datetimes

- This repository uses `datetime.utcnow()` (naive UTC with no timezone info) to store into the DB, and returns it as-is with no conversion on lookup either — unlike the NestJS implementation, which converts to and stores as KST (UTC+9), the FastAPI/SQLAlchemy implementation **unifies everything as UTC**, and the client converts the timezone if needed.
- For a new project, using `datetime.now(timezone.utc)` (a timezone-aware datetime) is recommended — `datetime.utcnow()` is deprecated as of Python 3.12.

```python
# recommended — timezone-aware UTC
from datetime import datetime, timezone

created_at = datetime.now(timezone.utc)
```

```python
# avoid — a naive datetime with no timezone info (deprecated in Python 3.12+)
created_at = datetime.utcnow()
```

- Both storage and lookup keep UTC — the principle that applying a different conversion at storage time vs. lookup time causes a double-conversion bug holds regardless of language.

### Null-handling rules

- A nullable field coming from the DB: `T | None`
- An optional parameter (a keyword argument with a default value): `T | None = None`, or a default on the type itself
- Python's counterpart to an `any` type is `Any` (`typing.Any`), and its use is forbidden — declare a concrete type or a `Union`/generic instead.

### ORM — inject `AsyncSession` in the Repository implementation

```python
class SqlAlchemyOrderRepository(OrderRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session
```

Since there's no dedicated DI container, `AsyncSession` is received directly as a constructor argument — a `Depends` factory handles this constructor call (see [module-pattern.md](architecture/module-pattern.md)).

### Complex types — a type alias or `Union`

```python
from typing import Union

OrderDomainEvent = Union[OrderPlaced, OrderCancelled]
```

On Python 3.10+, the `|` syntax also works: `OrderDomainEvent = OrderPlaced | OrderCancelled`.

---

## 5. REST API endpoint design rules

### URL structure — resource-centric, plural nouns

A URL expresses **a resource (a noun), not an action (a verb)**. The HTTP method expresses the action.

```
# correct approach
GET    /orders              list orders
GET    /orders/{order_id}   look up a single order
POST   /orders              create an order
PUT    /orders/{order_id}   fully update an order
PATCH  /orders/{order_id}   partially update an order
DELETE /orders/{order_id}   delete an order

# incorrect approach
GET    /getOrders            never put a verb in the URL
POST   /createOrder          never put a verb in the URL
GET    /order/{order_id}     singular form is forbidden — always plural
```

Fixing the resource prefix on the router itself via `APIRouter(prefix="/orders", tags=["Order"])` lets each route function just write a relative path, like `@router.get("/{order_id}")`.

### HTTP methods and response codes

| Method | Purpose | Success code | Response body |
|--------|------|----------|----------|
| `GET` | Look up a resource | 200 OK | Present |
| `POST` | Create a resource | 201 Created | Optional |
| `PUT` | Fully update a resource | 200 OK | Present |
| `PATCH` | Partially update a resource | 200 OK | Present |
| `DELETE` | Delete a resource | 204 No Content | None |

Specify `status_code` explicitly on the route decorator, as in `@router.post("", status_code=201, ...)` — never leave FastAPI's default (200) as-is.

### Non-CRUD actions — a sub-resource path

```
POST   /orders/{order_id}/cancel        cancel an order
POST   /orders/{order_id}/refund        refund an order
POST   /accounts/{account_id}/suspend   suspend an account
```

### Hierarchical relationships — nested resources

Nest only up to 2 levels; split anything deeper into a top-level resource.

```
# correct approach — 2-level nesting
GET  /orders/{order_id}/items
GET  /orders/{order_id}/items/{item_id}

# incorrect approach — nesting 3+ levels
GET  /users/{user_id}/orders/{order_id}/items/{item_id}
# → split into a top-level resource instead
GET  /order-items/{item_id}
```

### List queries — pagination and filtering

```
GET /orders?page=0&take=20&status=pending&status=paid
```

- Pagination: declare `page` (0-based) and `take` (page size) directly as route-function arguments via query parameters (`page: int = 0, take: int = 20`).
- Filters: passed as querystring parameters, applied only when a value is present.

### URL naming rules

- **Plural nouns**: `/orders`, `/users` (singular form is forbidden)
- **kebab-case**: `/order-items`, `/payment-methods` (the Python code itself is `snake_case`, but the URL path keeps kebab-case — the language convention and the URL convention are separate)
- **Lowercase only**
- **No trailing slash**: `/orders/` (bad) → `/orders` (good)
- **No file extension**: `/orders.json` (bad) → `/orders` (good)

### Deprecated endpoints

In FastAPI, specifying `deprecated=True` on the route decorator automatically shows a strikethrough and a "Deprecated" badge in the OpenAPI schema and `/docs` (Swagger UI).

```python
@router.post("", status_code=201, deprecated=True)
async def create_order(...) -> CreateOrderResponse: ...
```

- Rather than deleting it immediately, mark it `deprecated=True` to give clients time to migrate.
- Log a call with `logger.warning(...)` to track any remaining usage.

---

## 6. Method naming and organization

### Route functions

- Use verbs such as `get`, `find`, `create`, `update`, `delete`, `suspend`, `cancel`, `transfer`
- All are `async def` with an explicit return type: `-> ResponseModel`
- No logic — only construct the Handler + call `execute()`, and convert the response

### Handler (Command/QueryHandler) method order

1. `__init__` — injects dependencies (Repository/Adapter/Technical Service ABCs), using the `self._xxx` private convention (a single leading underscore)
2. `async def execute(self, cmd_or_query) -> ResultType` — the public orchestration method
3. Private helper methods (as needed) — a single leading underscore

```python
class CancelOrderHandler:
    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelOrderCommand) -> None:
        ...
```

### The Handler method's return type — always explicit

```python
# correct approach
async def execute(self, query: GetOrderQuery) -> GetOrderResult: ...
async def execute(self, cmd: CancelOrderCommand) -> None: ...

# incorrect approach — missing return type
async def execute(self, query: GetOrderQuery): ...
```

### A private environment-branching helper

```python
def _payment_api_url() -> str:
    if os.getenv("APP_ENV") == "production":
        return "https://payment.api.example.com"
    return "https://payment.dev.api.example.com"
```

---

## 7. Import organization pattern

### Absolute imports by default; relative imports allowed within the same layer

This repository's actual code (`examples/src/account/`) mixes relative imports (`from ...domain.account import Account`) within a domain package and absolute imports (`from src.database import get_session`) when referencing another top-level package. Follow these criteria.

- **Within the same domain package** (`domain/` → `domain/`, `application/` → `domain/`, etc.): a relative import is fine — moving the whole package under a different name doesn't break internal references.
- **Referencing another top-level package** (`src.database`, `src.common`, `src.auth`, another domain's `application/adapter/`): use an absolute import (`from src.xxx import ...`) — climbing several levels with a relative import (`from ....database import ...`) hurts readability.

```python
# src/account/interface/rest/account_router.py — the actual code pattern
from ...application.command.deposit_handler import DepositCommand, DepositHandler   # inside the same domain — relative
from ....database import get_session                                                  # another top-level package — relative (4 levels) is allowed too, but
from src.database import get_session                                                  # this form is also allowed if an absolute import reads more clearly
```

### Group order — standard library/third-party → internal modules

```python
# 1. standard library + third-party packages
from dataclasses import dataclass
from datetime import datetime

from fastapi import APIRouter, Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

# 2. internal modules (relative or absolute)
from ...domain.errors import OrderNotFoundError
from ...domain.order_repository import OrderRepository
```

Automating group order and alphabetical sorting via `isort`/`ruff --select I` is recommended.

### Circular imports — resolved by deferring a top-level import

Python has no syntax corresponding to NestJS's `forwardRef()`. Instead, resolve it with `TYPE_CHECKING` (when only a type hint is needed) or a function-local import (when the actual value is needed at runtime). Details: [module-pattern.md](architecture/module-pattern.md).

### `__init__.py` — leave it empty, or use it only as a package marker

Never re-export a submodule from `__init__.py` — if the import path splits into two forms (accessible via both the package path and the submodule path), it becomes confusing which one is the canonical path.

---

## 8. Pydantic response model / OpenAPI documentation pattern

FastAPI auto-generates the OpenAPI schema and Swagger UI (`/docs`) from just the Pydantic models and route signatures, with no manual assembly procedure like NestJS's `@ApiProperty`/`DocumentBuilder`. In this repository, the only things that must be written by hand are **the Pydantic model fields and the route decorator arguments**.

### `response_model` — always specified on the route decorator

```python
@router.get("/{order_id}", response_model=GetOrderResponse)
async def get_order(order_id: str, ...) -> GetOrderResponse: ...
```

Keep `response_model` and the function's return-type hint identical — specifying both gets you both FastAPI's actual field-filtering serialization (preventing unnecessary internal fields from being exposed) and the benefit of a static type checker (mypy).

### Request models — add descriptions/constraints via `Field(...)`

```python
from pydantic import BaseModel, Field


class CreateOrderRequest(BaseModel):
    description: str = Field(..., min_length=1, max_length=200, description="Order description")
    delivery_type: Literal["standard", "express"] = Field(..., description="Delivery type")
```

- `Field(..., min_length=..., max_length=...)` corresponds to NestJS's combination of `@IsString()` + `@MinLength()` + `@MaxLength()` — Pydantic handles validation and documentation with the same declaration.
- `Literal["standard", "express"]` corresponds to NestJS's `@IsEnum()` + `@ApiProperty({ enum: [...] })`.

### Optional fields — `| None = None` + `Field(None, description=...)`

```python
class FindOrdersRequestQuery(BaseModel):
    keyword: str | None = Field(None, description="Search keyword")
    page: int = Field(0, ge=0, description="Page number (0-based)")
    take: int = Field(20, ge=1, le=100, description="Page size")
```

In FastAPI, a querystring parameter can be declared directly as a route-function argument with no separate DTO class (`page: int = 0, take: int = Query(20, ge=1, le=100)`) — once the number of fields grows, group them into a Pydantic model like above and inject it via `Depends()`.

### Array/nullable fields — the type hint is the documentation

```python
class GetOrdersResult:
    orders: list[OrderSummaryResult]   # corresponds to NestJS's @ApiProperty({ type: [ItemClass] })
    total_count: int
```

```python
class GetOrderResponse(BaseModel):
    description: str | None   # corresponds to NestJS's @ApiProperty({ nullable: true, type: String })
```

With no separate decorator, the type hint itself is converted into the OpenAPI `nullable`/`items` schema.

### Result (Application) vs. Response (Interface) — where fields are declared

Since the Application layer's Result is a `dataclass`, it never has a Pydantic `Field`/validation decorator attached — validation/documentation is written only on the Interface layer's Pydantic model (`schemas.py`). Opposite to NestJS's "write `@ApiProperty` on the Query/Result class," the FastAPI implementation places the documentation responsibility in the Interface layer.

### deprecated / summary / description

```python
@router.get(
    "/{order_id}",
    response_model=GetOrderResponse,
    summary="Look up a single order",
    description="Looks up a single order's info by order ID.",
)
async def get_order(order_id: str, ...) -> GetOrderResponse: ...
```

---

## 9. Logger pattern

### Always `getLogger(__name__)` at the module's top level

```python
import logging

logger = logging.getLogger(__name__)
```

Unlike NestJS's `new Logger(XxxController.name)` (a class field), Python's standard `logging` idiomatically obtains a logger per module — it's never kept as a separate field inside a class.

### Structured JSON logs — passing fields via `extra=`

```python
# an error log
logger.exception("Failed to send notification email: account_id=%s", account_id)

# an info log — passing structured fields via extra= (snake_case field names)
logger.info("Order completed", extra={"order_id": order_id, "amount": amount})
```

`%s` string interpolation alone is readable enough for a human, but applying structured logging (a JSON formatter) merges the fields carried in `extra=` as top-level JSON keys, letting CloudWatch/Datadog filter on them directly — details: [observability.md](architecture/observability.md).

---

## 10. Comment style

- Write a business-domain explanation as an inline comment (`#`), in English
- A docstring (`"""..."""`) is used only for a one-line summary on a public API (a Handler's `execute()`, a Repository ABC's abstract method) — not as strict as NestJS's "no JSDoc" rule, but a short inline comment is preferred over a verbose multi-line docstring.
- A long Handler method uses section comments to mark logical divisions.

```python
async def execute(self, cmd: CancelOrderCommand) -> None:
    # Look up the order
    orders, _ = await self._repo.find_orders(page=0, take=1, order_id=cmd.order_id)
    order = orders[0] if orders else None
    if order is None:
        raise OrderNotFoundError(cmd.order_id)

    # Business-rule validation and the state change are delegated to the Aggregate
    order.cancel(cmd.reason)

    # Save — including loading the Outbox
    await self._repo.save(order)
```

---

## 11. Commit message convention

Follows exactly the same [Conventional Commits](https://www.conventionalcommits.org/) rules as the root [conventions.md](../../../docs/conventions.md) — there is no per-language variation.

### Message structure

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

### List of types

| type | Description | Example |
|------|------|------|
| `feat` | Adds a new feature | `feat(order): add order-cancellation feature` |
| `fix` | Fixes a bug | `fix(order): fix order status not updating after payment completes` |
| `refactor` | Changes code structure with no behavior change | `refactor(order): move lookup logic into the Repository` |
| `docs` | Documentation-only change | `docs: update the README's project-structure section` |
| `test` | Adds or modifies a test | `test(order): add a unit test for the order-cancellation invariant` |
| `chore` | Non-code work such as build, CI, dependencies | `chore(deps): upgrade the fastapi version` |
| `style` | A change with no effect on behavior, such as code formatting | `style: fix import ordering (ruff)` |
| `perf` | A performance improvement | `perf(order): optimize the order-list lookup query` |

### scope / description rules

- Use the **service domain name** as the scope: `order`, `account`, `payment`, `auth`, etc.
- For a change spanning multiple domains, omit the scope or use a higher-level concept
- The description is a plain descriptive sentence, in English, with no capitalized first letter required and no trailing period

### BREAKING CHANGE

```
feat(order)!: change the order response schema

BREAKING CHANGE: renamed GetOrderResponse's total_price field to total_amount
```

### Examples

```
feat(order): add order-cancellation feature

fix(order): fix order status not updating after payment completes (#123)

refactor(account): unify find_by_id/find_all into find_accounts

Following the root convention in repository-pattern.md (a single find<Noun>s
method), the lookup path is merged into one and call sites are updated to
the take=1 pattern.

docs: add the enum-placement rule

test(order): add a unit test for the order-creation invariant
```

---

## 12. Branch and PR convention

Follows exactly the same Conventional Branch rules as the root [conventions.md](../../../docs/conventions.md).

### Branch naming

```
<type>/<scope>-<short-description>
```

| type | Purpose | Example |
|------|------|------|
| `feat` | New feature development | `feat/order-cancel` |
| `fix` | Bug fix | `fix/order-status-update` |
| `refactor` | Refactoring | `refactor/account-repository-find-unify` |
| `docs` | Documentation change | `docs/architecture-adapter-pattern` |
| `test` | Adding/modifying a test | `test/order-cancel-invariant` |
| `chore` | Build, CI, dependencies | `chore/deps-fastapi-upgrade` |

**Rules:**
- Every word is written in `kebab-case` (separate from the `snake_case` used in Python code).
- Omit the scope if it isn't needed.
- Branch off of `main`, and never commit/push directly to `main`.

### PR workflow

```
1. Create a new branch off main
   git checkout main && git pull origin main
   git checkout -b <type>/<scope>-<short-description>

2. Commit after working (Conventional Commits format)
   git add <files>
   git commit -m "<type>(<scope>): <description>"

3. Push to the remote
   git push -u origin <branch-name>

4. Create a PR against the main branch
   gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR title / body

Write the PR title matching the Conventional Commits format.

```markdown
## Summary
- A 1-3 line summary of the change

## Test plan
- [ ] Test item 1
- [ ] Test item 2
```

### Merge strategy

- **Squash and merge** is used by default.
- The remote branch is automatically deleted after merging.

---

## 13. Testing pattern

For the detailed principles (the 3-layer structure, the mocking strategy, testcontainers), see [architecture/testing.md](architecture/testing.md). This section only summarizes the naming/placement rules.

### Unit tests — the Domain layer

Verified by instantiating the Aggregate directly, with no framework or mocks.

```python
# tests/unit/domain/test_order.py
import pytest

from src.order.domain.errors import OrderAlreadyCancelledError, OrderMustHaveAtLeastOneItemError
from src.order.domain.order import Order


def test_create_주문_항목이_비어있으면_에러를_던진다() -> None:
    with pytest.raises(OrderMustHaveAtLeastOneItemError):
        Order.create(user_id="user-1", items=[])


def test_cancel_이미_취소된_주문을_다시_취소하면_에러를_던진다() -> None:
    order = Order.create(user_id="user-1", items=[make_item()])
    order.cancel("change of mind")

    with pytest.raises(OrderAlreadyCancelledError):
        order.cancel("change of mind")
```

### Unit tests — an Application Handler

Replace the Repository/Adapter/Technical Service ABC with `unittest.mock.AsyncMock`.

```python
# tests/unit/application/test_cancel_order_handler.py
from unittest.mock import AsyncMock

import pytest

from src.order.application.command.cancel_order_handler import CancelOrderCommand, CancelOrderHandler
from src.order.domain.errors import OrderNotFoundError


@pytest.mark.asyncio
async def test_execute_주문이_존재하지_않으면_에러를_던진다() -> None:
    repo = AsyncMock()
    repo.find_orders.return_value = ([], 0)
    handler = CancelOrderHandler(repo)

    with pytest.raises(OrderNotFoundError):
        await handler.execute(CancelOrderCommand(order_id="non-existent", reason="change of mind"))
```

### E2E tests — `httpx.ASGITransport` + testcontainers

```python
# tests/test_order_e2e.py
from httpx import ASGITransport, AsyncClient
from testcontainers.postgres import PostgresContainer


@pytest_asyncio.fixture(scope="session")
async def client():
    with PostgresContainer("postgres:16-alpine") as postgres:
        ...
        app.dependency_overrides[get_session] = override_get_session
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac


@pytest.mark.asyncio
async def test_order_cancel_success(client) -> None:
    response = await client.post(f"/orders/{order_id}/cancel", json={"reason": "change of mind"})
    assert response.status_code == 204
```

`pytest.ini`'s `asyncio_mode = auto` automatically includes every `async def test_*` as a `pytest-asyncio` target — there's no need to attach `@pytest.mark.asyncio` to each test individually (this repository mixes that in with the explicit-attachment style too — the team should settle on one).

### Test file placement

```
tests/
  unit/
    domain/
      test_order.py
    application/
      test_cancel_order_handler.py
  test_order_e2e.py
```

### Test naming pattern

```
test_<method>_<condition>_<expected_result>
e.g.: test_cancel_이미_취소된_주문을_다시_취소하면_에러를_던진다
e.g. (an English style is allowed too): test_cancel_order_when_already_cancelled_then_raises
```

If the existing E2E tests use an English style (`test_deposit_success`), keep new tests in that same file consistently in the English style too — keep a single style consistent per file.
