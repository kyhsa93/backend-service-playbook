# Rate Limiting

> For the language-agnostic principles of rate limiting (global defaults, differentiating write/read, excluding internal endpoints, response headers), see the "4. Rate Limiting Principles" section of the root [conventions.md](../../../../docs/conventions.md). This document covers how to implement those principles in FastAPI.

## Implementation status — implemented with `slowapi`

`slowapi` has been added to `examples/requirements.txt`, and the content of this document is reflected as-is in `examples/src/common/rate_limit.py` (the `Limiter` instance), `examples/src/config/rate_limit_config.py` (environment-variable-based limits), `examples/main.py` (global middleware/exception handler registration), and `examples/src/account/interface/rest/account_router.py` (`@limiter.limit()` on each write endpoint).

---

## `slowapi` — Starlette-based, the most idiomatic choice for FastAPI

Since FastAPI runs on top of Starlette, the framework already provides a middleware-registration point (`app.add_middleware`) and an exception-handling point (`@app.exception_handler`), the way NestJS's `@nestjs/throttler` does. `slowapi` is a thin wrapper layered on those two points, and it works in-memory without Redis, which suits local development well.

```bash
pip install slowapi
```

### Global registration — `src/common/rate_limit.py` + `main.py`

Putting the `Limiter` instance directly in `main.py` would create a circular import, since `account_router.py` would need to import `main` to use `@limiter.limit(...)` (see the "Python's circular imports" section of [module-pattern.md](module-pattern.md)) — because `main.py` already imports `account_router`. So the `Limiter` singleton lives in the shared-infrastructure location `src/common/rate_limit.py`, and both `main.py` and each router import it from there.

```python
# src/common/rate_limit.py — actual code
from slowapi import Limiter
from slowapi.util import get_remote_address

from ..config.rate_limit_config import RateLimitConfig

rate_limit_config = RateLimitConfig()
limiter = Limiter(key_func=get_remote_address, default_limits=[rate_limit_config.default_limit])
```

```python
# main.py — actual code, global registration
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware

from src.common.rate_limit import limiter

app = FastAPI(title="Account Service", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.include_router(auth_router)
app.include_router(account_router)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    ...


# Must be added "after" the Correlation ID middleware is registered — since Starlette makes
# a later-registered middleware the outer layer (the last one added receives the request
# first), adding it here lets SlowAPIMiddleware intercept the request before the Correlation
# ID is assigned, returning 429 early once the limit is exceeded.
app.add_middleware(SlowAPIMiddleware)
```

`key_func=get_remote_address` limits based on the client IP. Since this repository already has JWT authentication in place ([authentication.md](authentication.md)), limiting based on the user ID obtained from `Depends(get_current_user)` would in principle be more accurate than IP (an IP can be shared by multiple users behind a NAT/proxy). However, `slowapi`'s `key_func` only receives the `Request` and has no access to FastAPI's `Depends` results, so switching to user-ID-based limiting would require parsing the `Authorization: Bearer` header directly inside `key_func` and calling `JwtAuthService().verify_token(...)` — this has the trade-off of duplicating the authentication-verification logic in two places (the router's `Depends(get_current_user)` and the rate limit's `key_func`), so at this repository's current scale, the practical choice of keeping `get_remote_address` was made. If traffic grows and the IP-sharing problem becomes a real issue, switch in the following direction.

```python
# future direction — limiting by user ID (not applied yet, see the trade-off above)
from src.auth.domain.errors import InvalidTokenError
from src.auth.infrastructure.jwt_auth_service import JwtAuthService


def _rate_limit_key(request: Request) -> str:
    auth_header = request.headers.get("authorization", "")
    if auth_header.lower().startswith("bearer "):
        try:
            return JwtAuthService().verify_token(auth_header.split(" ", 1)[1])
        except InvalidTokenError:
            pass
    return get_remote_address(request)
```

### Per-endpoint limits — decorators

`@limiter.limit(...)` must be declared **before** `@router.post` (since decorators apply bottom-up, this effectively makes it the outer layer). `slowapi` requires a `request: Request` parameter in the route function's signature to work.

```python
# src/account/interface/rest/account_router.py — actual code
from fastapi import APIRouter, Depends, Request

from ....common.rate_limit import limiter, rate_limit_config

@router.post("/{account_id}/withdraw", status_code=201, response_model=TransactionResponse)
@limiter.limit(rate_limit_config.write_limit)   # write APIs are stricter than reads (conventions.md principle)
async def withdraw(
    request: Request,   # slowapi identifies the client via this parameter
    account_id: str,
    body: WithdrawRequest,
    current_user: CurrentUser = Depends(get_current_user),
    repo: SqlAlchemyAccountRepository = Depends(_repo),
) -> TransactionResponse:
    ...


@router.get("/{account_id}", response_model=GetAccountResponse)
async def get_account(account_id: str, ...) -> GetAccountResponse:
    ...   # no separate decorator — only the global default_limits (RATE_LIMIT_DEFAULT) apply
```

`@limiter.limit(rate_limit_config.write_limit)` is applied to all 6 write endpoints (all `POST`): `create_account`/`deposit`/`withdraw`/`suspend_account`/`reactivate_account`/`close_account`. The query endpoints `get_account`/`get_transactions` (both `GET`) have no separate decorator and only receive the global default via `Limiter(default_limits=...)` — this upholds the "principle" of keeping queries more permissive than writes, while `config/rate_limit_config.py` doesn't separately expose a read-only value (if needed, extend `RateLimitConfig` with a `read_limit` field).

`sign_up`/`sign_in` in `auth_router.py` are also `POST`, so `@limiter.limit(rate_limit_config.write_limit)` is applied to them the same way — `sign_in` in particular, since it goes through password verification ([authentication.md](authentication.md)), is an endpoint that can be a target of brute-force attacks, and should receive a stricter write-tier limit than the global default (`RATE_LIMIT_DEFAULT`).

### Excluding internal/health-check endpoints

`slowapi` has no dedicated decorator like NestJS's `@SkipThrottle()` — instead, place the health-check route in a separate `APIRouter` that has no `Limiter` registered, or handle it conditionally with an `exempt_when` callback.

> This section is still forward-looking — the `/health/live`, `/health/ready` endpoints don't exist in this repository yet (a separate gap already noted in [graceful-shutdown.md](graceful-shutdown.md)). Below is guidance to apply together once those endpoints are added.

```python
# main.py — return early for /health/* before the global middleware, or exclude via an exempt callback
@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}   # note: without @limiter.limit() on this route, only default_limits applies
```

`default_limits` also applies via the global middleware to routes without `@limiter.limit()`, so fully excluding a route requires custom logic that manipulates `request.state.view_rate_limit` — at this repository's scale, since the health-check response time is very short, it's also a practical choice to just absorb it under a generous `default_limits` rather than bothering with an exception.

### Response headers

`slowapi` automatically fills in the standard headers once `SlowAPIMiddleware` is registered — the same headers the root principle ([conventions.md](../../../../docs/conventions.md)) requires.

| Header | Description |
|------|------|
| `X-RateLimit-Limit` | The maximum number of requests allowed |
| `X-RateLimit-Remaining` | The number of requests remaining |
| `X-RateLimit-Reset` | Time remaining (in seconds) until the limit resets |

When the limit is exceeded, `slowapi` returns `429 Too Many Requests`.

## Position in this repository's middleware chain

Adding rate limiting to the request pipeline laid out in [cross-cutting-concerns.md](cross-cutting-concerns.md) produces the following order — blocking a request that exceeds the limit even before Correlation ID injection reduces wasted resources.

```
Request → [0. SlowAPIMiddleware — immediate 429 once exceeded] → [1. Correlation ID Middleware] → [2. Depends(auth)] → [3. Pydantic validation] → [4. Route function] → Response
```

## Adjusting operational values

```python
# src/config/rate_limit_config.py — actual code. Follows the per-concern config-class pattern from config.md
import os


class RateLimitConfig:
    def __init__(self) -> None:
        self.default_limit = os.getenv("RATE_LIMIT_DEFAULT", "100/minute")
        self.write_limit = os.getenv("RATE_LIMIT_WRITE", "20/minute")
```

Separating these into environment variables as above, instead of hardcoded string literals, lets the values be adjusted in staging/production — apply this together with the fail-fast validation pattern from [config.md](config.md). `RateLimitConfig()` is instantiated once at app startup by `src/common/rate_limit.py`, which passes the values to `Limiter(default_limits=[...])` and the `@limiter.limit(...)` decorators, so changing a value only requires setting the `RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE` environment variables in the deployment environment and restarting the process, with no code change — this supports deploy-time configuration the same way as go (`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`) and nestjs (`THROTTLE_*`, see "Adjusting operational values" in [../../../nestjs/docs/architecture/rate-limiting.md](../../../nestjs/docs/architecture/rate-limiting.md)). `examples/tests/conftest.py` overrides `RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE` with generous values, to avoid the situation where e2e tests make dozens of requests in a short time from the same process/same client IP — the production defaults (`100/minute`/`20/minute`) remain unchanged.

## Principles

- **Register defaults via global middleware**: apply a minimum limit to every endpoint with `SlowAPIMiddleware` + `default_limits`.
- **Make write APIs stricter**: `POST`/`PUT`/`DELETE` routes individually specify a lower limit than `GET` via `@limiter.limit()`.
- **Weigh the trade-off before switching to user-ID-based limiting**: an IP is inaccurate since it can be shared by multiple users behind a NAT/proxy, but obtaining the user ID inside `slowapi`'s `key_func` duplicates the JWT-verification logic already in `Depends(get_current_user)` — this repository makes the practical choice of keeping `get_remote_address` at its current scale (see the "Global registration" section above).
- **Manage limits via environment variables**: never hardcode them; separate them into a `config/` class.

---

### Related documents

- [conventions.md](../../../../docs/conventions.md) — the language-agnostic rate-limiting principles (root)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — its position in the middleware chain
- [authentication.md](authentication.md) — the JWT authentication flow, and the prerequisite for switching to user-ID-based limiting
- [module-pattern.md](module-pattern.md) — why `Limiter` lives in `common/` to avoid a circular import
- [graceful-shutdown.md](graceful-shutdown.md) — `/health/live`/`/health/ready` are defined in `main.py` without a rate-limit `Depends`, so they are already excluded from limiting
- [config.md](config.md) — managing per-environment limit values

---

The harness's `rate-limit-wired` rule (`../../harness/rules/rate_limit_wired.py`) verifies against the state where `Limiter` is only defined but never wired up in `main.py` (`app.state.limiter`/the `RateLimitExceeded` handler/`SlowAPIMiddleware`), or where `@limiter.limit(...)` is applied to no route at all, leaving it as dead code.
