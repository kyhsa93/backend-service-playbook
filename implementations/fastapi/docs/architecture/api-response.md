# API Response Structure

> Framework-agnostic principles: [../../../../docs/architecture/api-response.md](../../../../docs/architecture/api-response.md)

FastAPI uses a Pydantic `BaseModel` as the response schema. Specifying `response_model=` on the router decorator makes FastAPI serialize the return value according to the schema and automatically reflect it in the OpenAPI docs.

---

## Pagination

Offset-based pagination is used by default.

| Parameter | Type | Description | Default |
|---------|------|------|--------|
| `page` | `int` | Page number (0-based) | 0 |
| `take` | `int` | Page size | 20 |

```
GET /accounts/{account_id}/transactions?page=0&take=20
```

`get_transactions` in `src/account/interface/rest/account_router.py` is the actual implementation.

```python
# actual code
@router.get("/{account_id}/transactions", response_model=GetTransactionsResponse)
async def get_transactions(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    page: int = 0,
    take: int = 20,
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> GetTransactionsResponse:
    ...
```

**Why `page` starts at 0:** the computation `offset = page * take` reads naturally. With `page=0`, `offset=0` fetches the first page. `SqlAlchemyAccountRepository.find_transactions` implements it the same way, with `stmt.offset(page * take).limit(take)`.

---

## List-query response shape

`GetTransactionsResponse` in `src/account/interface/rest/schemas.py` precisely follows the root rule.

```python
class TransactionSummaryResponse(BaseModel):
    transaction_id: str
    type: str
    amount: MoneySchema
    created_at: datetime


class GetTransactionsResponse(BaseModel):
    transactions: list[TransactionSummaryResponse]   # plural of the domain object name
    count: int                                        # total count after filters are applied
```

```json
{
  "transactions": [
    { "transaction_id": "abc123", "type": "DEPOSIT", "amount": {"amount": 10000, "currency": "KRW"}, "created_at": "..." }
  ],
  "count": 42
}
```

**Principles:**
- The key name is the **plural of the domain object name** (`transactions`, `accounts`) — generic keys such as `result`, `data`, `items` are never used.
- `count` is the total count after filters are applied, and may not equal the length of the array returned on the current page (`len(transactions)`).

The harness's `no-generic-response-keys` rule (`../../harness/rules/no_generic_response_keys.py`) mechanically enforces this principle, failing if a `list[...]` field name in a Pydantic `BaseModel` under `interface/rest/` is `result`/`data`/`items`.

---

## Single-item lookup response shape

`GetAccountResponse` is an example.

```python
class GetAccountResponse(BaseModel):
    account_id: str
    owner_id: str
    email: str
    balance: MoneySchema
    status: str
    created_at: datetime
    updated_at: datetime
```

It's never wrapped in a generic wrapper (`{"success": true, "data": {...}}`). FastAPI serializes the Pydantic response model as-is into the top-level JSON.

**Why a generic wrapper isn't used:** the HTTP status code is responsible for distinguishing an error from a normal response. A wrapper would be redundant and adds an unnecessary unwrapping layer for the client.

---

## The Result object — separating the Application layer from the Interface layer

`GetAccountHandler` (`src/account/application/query/get_account_handler.py`) doesn't return the domain `Account` object directly — it converts it into the `GetAccountResult` dataclass in `application/query/result.py` and returns that.

```python
# src/account/application/query/result.py
@dataclass
class MoneyResult:
    amount: int
    currency: str


@dataclass
class GetAccountResult:
    account_id: str
    owner_id: str
    email: str
    balance: MoneyResult
    status: str
    created_at: datetime
    updated_at: datetime
```

The router (`account_router.py`) wraps this `GetAccountResult` again into the Pydantic response model (`GetAccountResponse`).

```python
result = await GetAccountHandler(repo).execute(GetAccountQuery(account_id=account_id, requester_id=current_user.user_id))
return GetAccountResponse(
    account_id=result.account_id,
    owner_id=result.owner_id,
    email=result.email,
    balance={"amount": result.balance.amount, "currency": result.balance.currency},
    status=result.status,
    created_at=result.created_at,
    updated_at=result.updated_at,
)
```

**Why the domain Aggregate isn't exposed directly as the response:**
- The `Account` domain object includes business logic (`deposit`, `withdraw`, ...) and an internal event buffer (`_events`). Serializing it would expose internal implementation to the outside.
- The `Result` dataclass only includes the fields needed for the query, making it lighter than the Aggregate and more flexible to change.

The separation of responsibilities across the three layers:

```
Account (domain)  →  GetAccountResult (application/query, dataclass)  →  GetAccountResponse (interface/rest, Pydantic BaseModel)
```

The harness's `query-handler-no-raw-aggregate` rule (`../../harness/rules/query_handler_no_raw_aggregate.py`) fails if a `*Handler.execute()` return-type annotation under `application/query/` is a type imported from `domain/` (an Aggregate, etc.), enforcing the principle that a domain Aggregate is never exposed directly as a response. It doesn't hardcode any specific domain name — it determines this structurally, by "whether the return type was imported from domain/".

---

## Single-item lookups also use the same Repository method

Both the single-item lookup endpoint (`GET /accounts/{account_id}`) and any internal lookup equivalent to a list query rely on a single `AccountRepository.find_accounts()` — a single-item lookup is done by calling it with the `account_id`+`owner_id` filters and `take=1`, then pulling out the first item of the result. Because the lookup path is unified into one, the implementation doesn't get duplicated as dynamic filters grow. See [repository-pattern.md](repository-pattern.md) for details.

---

## The dynamic filter-condition pattern

In a list-query query, a condition is applied only when a value is present. `SqlAlchemyAccountRepository.find_accounts` follows this pattern.

```python
async def find_accounts(
    self, page: int, take: int,
    account_id: str | None = None, owner_id: str | None = None, status: list[str] | None = None,
) -> tuple[list[Account], int]:
    stmt = select(AccountModel).where(AccountModel.deleted_at.is_(None))

    if account_id:
        stmt = stmt.where(AccountModel.id == account_id)
    if owner_id:
        stmt = stmt.where(AccountModel.owner_id == owner_id)
    if status:
        stmt = stmt.where(AccountModel.status.in_(status))
    ...
```

Putting a `None`/empty value into a condition unconditionally can unintentionally result in a full scan or cause a query error. Each condition is wrapped in an `if` guard.

---

## Machine-readable API documentation (OpenAPI)

> Root principle + completeness bar: [../../../../docs/architecture/api-response.md](../../../../docs/architecture/api-response.md#machine-readable-api-documentation-openapi)

FastAPI auto-generates an OpenAPI schema from `FastAPI(...)`, route decorators, and Pydantic
models with no extra setup (see [bootstrap.md](bootstrap.md)) — but that auto-generated
skeleton is not the same thing as it being *documented*. A bare `@router.post(...)` with no
`summary=`/`description=`/`responses=` still renders a page at `/docs`, just with nothing
useful on it. This repository closes that gap explicitly:

```python
# main.py — actual code
app = FastAPI(
    title="Account Service",
    description="API documentation for the DDD-based Account/Card/Payment/Auth domain example service",
    version="0.1.0",
    lifespan=lifespan,
)
```

```python
# src/account/interface/rest/account_router.py — actual code
router = APIRouter(
    prefix="/accounts",
    tags=["Account"],
    dependencies=[Depends(get_current_user)],
    # A router-level `responses=` is merged into every route on this router by FastAPI —
    # since every route here requires get_current_user, 401 applies uniformly without
    # repeating it per-route (the same effect as nestjs's class-level `@ApiUnauthorizedResponse`).
    responses={401: {"model": ErrorResponse, "description": "The bearer token is missing, malformed, or invalid (`INVALID_TOKEN`)."}},
)


@router.post(
    "/{account_id}/deposit",
    status_code=201,
    response_model=TransactionResponse,
    summary="Deposit money into an account",
    description="Credits the given amount to the account and records a `DEPOSIT` transaction.",
    responses={
        400: {"model": ErrorResponse, "description": "One of: the amount is not a positive integer (`INVALID_AMOUNT`), or the account is not active (`DEPOSIT_REQUIRES_ACTIVE_ACCOUNT`)."},
        404: {"model": ErrorResponse, "description": "No account exists with the given `account_id` for this requester (`ACCOUNT_NOT_FOUND`)."},
        422: {"model": ErrorResponse, "description": "Request validation failed (`VALIDATION_FAILED`)."},
    },
)
@limiter.limit(rate_limit_config.write_limit)
async def deposit(...):
    ...
```

`summary=`/`description=` and `responses={...}` are declared explicitly as keyword arguments
on every route decorator (not pulled from the function's docstring) — this repository has no
prior docstring convention for routes, and keeping error status codes structured as dict keys
rather than parsed out of prose is easier to check mechanically (see the harness rule below).
Every non-2xx `responses=` entry is cross-checked against that specific handler's real
error-mapping (its `domain/errors.py` exceptions and the `@app.exception_handler`s registered
in `main.py`, see [error-handling.md](error-handling.md)) — not guessed generically. The
shared `ErrorResponse` Pydantic model (`src/common/error_response.py`) is referenced via
`"model": ErrorResponse` in every entry, and each of the 4 fields on it carries its own
`Field(description=...)`.

Every request/response Pydantic model field also has a `Field(description=...)` — see
`src/account/interface/rest/schemas.py`, `src/card/.../schemas.py`,
`src/payment/.../schemas.py`, `src/auth/.../schemas.py`. A bare `field: str` with no
description forces a reader of `/docs` to guess its meaning from the field name alone.

**The harness's `api-documentation` rule** (`../../harness/rules/api_documentation.py`)
fails a route if its decorator's keywords don't include both `summary=` and `description=`, or
if no non-2xx status appears in `responses={...}` (checked as the union of the route's own
`responses=` and the router-level `responses=` on `APIRouter(...)`). It doesn't know which
status codes are "correct" for a given handler — that cross-check is a human-review
responsibility — only that *some* failure path is documented, not just the success response.

---

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository method design, the `find<Noun>s` unification principle
- [layer-architecture.md](layer-architecture.md) — Query Handler and Result object placement
