# Cross-Cutting Concerns

> Framework-agnostic principles: [../../../../docs/architecture/cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md)

## Correlation ID middleware and JWT `Depends` authentication

`main.py` has `correlation_id_middleware` (Correlation ID injection + request logging) registered via `@app.middleware("http")`, and also handles error conversion via `@app.exception_handler`. Authentication doesn't trust an `X-User-Id` via `Header(...)` — it validates a JWT via `Depends(get_current_user)` — see [authentication.md](authentication.md). Below are the details of how this pipeline is assembled using FastAPI's middleware/`Depends` mechanisms.

---

## Request pipeline — the FastAPI counterpart

FastAPI has no separate layers like NestJS's Guard/Pipe/Interceptor. Instead, the same responsibilities are split between **`Middleware`** (common pre/post-processing for every request) and **`Depends`** (per-route dependency injection — authentication, validation).

```
Request → [1. Middleware] → [2. Depends(auth)] → [3. Pydantic request-model validation] → [4. Route function] → [5. Middleware post-processing] → Response
```

| Stage | FastAPI mechanism | Role |
|------|-----------------|------|
| 1. Pre-processing | `@app.middleware("http")` | Injects a Correlation ID into every request |
| 2. Authentication | `Depends(get_current_user)` | Validates the token, extracts user info |
| 3. Input validation | Pydantic request model (automatic) | Validates types/required values — FastAPI returns 422 automatically |
| 4. Route function | `interface/rest/*_router.py` | Calls the Handler, propagates errors |
| 5. Post-processing | Code after `response` in the same middleware function | Request logging, response-time measurement |

---

## Correlation ID — `contextvars` + middleware

The Python standard-library counterpart to Node's `AsyncLocalStorage` is `contextvars`. Once set by the middleware when a request comes in, it can be accessed from anywhere down the entire async call chain, with no need to pass it as a function argument.

```python
# src/common/correlation.py — actual code
from contextvars import ContextVar
import uuid

_correlation_id: ContextVar[str | None] = ContextVar("correlation_id", default=None)


def get_correlation_id() -> str:
    return _correlation_id.get() or "unknown"


def set_correlation_id(value: str) -> None:
    _correlation_id.set(value)


def generate_correlation_id() -> str:
    return uuid.uuid4().hex
```

```python
# main.py — actual code
import time

from fastapi import Request

from src.common.correlation import generate_correlation_id, set_correlation_id


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    correlation_id = request.headers.get("x-correlation-id") or generate_correlation_id()
    set_correlation_id(correlation_id)

    start = time.monotonic()
    response = await call_next(request)
    duration_ms = (time.monotonic() - start) * 1000

    response.headers["x-correlation-id"] = correlation_id
    logger.info(
        "%s %s",
        request.method,
        request.url.path,
        extra={
            "status_code": response.status_code,
            "duration_ms": round(duration_ms, 1),
            "correlation_id": correlation_id,
        },
    )
    return response
```

Because `contextvars.ContextVar` keeps an independent context per `asyncio` task, Correlation IDs from multiple requests being handled concurrently never mix. Downstream logging (`observability.md`) pulls it out with `get_correlation_id()` and includes it on every log line.

---

## Authentication — `Depends` serves as the Guard

The principle that authentication is handled only in the Interface layer, with Application/Domain unaware of the auth context, holds the same way in FastAPI. The equivalent of NestJS's class-level `@UseGuards(AuthGuard)` is hanging a shared `Depends` on the entire router.

```python
# src/account/interface/rest/account_router.py — actual code. Applies a shared dependency to the whole router
# (no risk of forgetting it on an individual route)
router = APIRouter(prefix="/accounts", tags=["Account"], dependencies=[Depends(get_current_user)])

@router.get("/{account_id}")
async def get_account(
    account_id: str,
    current_user: CurrentUser = Depends(get_current_user),  # when the actual user info is needed as a route-function argument
    ...
) -> GetAccountResponse: ...
```

→ See [authentication.md](authentication.md) for the token-validation logic itself.

---

## Input validation — handled automatically by Pydantic

When FastAPI's request body/query parameters are declared as Pydantic models (`interface/rest/schemas.py`), a `422 Unprocessable Entity` is returned automatically on a type mismatch or missing required value. No separate Pipe layer is needed.

```python
# src/account/interface/rest/schemas.py — follows this pattern
class CreateAccountRequest(BaseModel):
    currency: str
    email: EmailStr   # Pydantic also automatically validates the email format
```

**Format validation is never conflated with business rules.** An invalid `email` format is 422 (Pydantic); depositing into an already-suspended account is 400 (`DepositRequiresActiveAccountError` in `domain/errors.py`) — the latter is the responsibility of the Application/Domain layer.

---

## HTTP request logging — handled centrally in the middleware

Logging is never done individually inside the route function (`account_router.py`). The post-processing block of the Correlation ID middleware above already logs the request method/path/processing time — centralizing the logging location leaves only the business flow in the route function.

---

## No middleware/logger use in the Domain layer

```python
# forbidden — using a logger/contextvars directly in the Domain layer (account.py)
from src.common.correlation import get_correlation_id  # ← forbidden

class Account:
    def deposit(self, amount: int) -> Transaction:
        logger.info(f"deposit called, correlation_id={get_correlation_id()}")  # ← forbidden
        ...
```

`src/account/domain/account.py` currently upholds this principle well — it has no logging and no framework imports at all. This purity must be preserved when adding new domain methods too.

---

## Principles

- **Use the FastAPI mechanism that matches the responsibility**: global pre-processing via `@app.middleware`, per-route authentication/dependencies via `Depends`, input validation via Pydantic models.
- **Pre-processing runs first, in the middleware**: Correlation ID injection must run before everything else.
- **Keep route functions pure**: they are only responsible for calling the Handler and converting the response. Never write logging/authentication logic directly inside a route function.
- **Shared dependencies at the router level**: hang them via `APIRouter(dependencies=[...])` to prevent them being missed on an individual route.

---

### Related documents

- [authentication.md](authentication.md) — JWT authentication details
- [observability.md](observability.md) — structured logging including the Correlation ID
- [error-handling.md](error-handling.md) — where error conversion happens (`@app.exception_handler`)
