# App Bootstrap

This repository's FastAPI implementation has no dedicated bootstrap function like NestJS's `main.ts` + `NestFactory.create()` — it's just constructing the `FastAPI(...)` instance at the module's top level and registering routers/exception handlers underneath it. This is the entirety of the actual `examples/main.py`.

```python
# main.py — actual code
import logging
import os
import time
from contextlib import asynccontextmanager

from src.config.validator import validate_env

validate_env()  # on failure, the process exits right here — no code after this runs

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402

from src.account.domain.errors import AccountError, AccountNotFoundError  # noqa: E402
from src.account.interface.rest.account_router import router as account_router  # noqa: E402
from src.auth.infrastructure.jwt_auth_service import set_jwt_secret  # noqa: E402
from src.auth.interface.rest.auth_router import router as auth_router  # noqa: E402
from src.common.aws_secret_service import AwsSecretService  # noqa: E402
from src.common.correlation import generate_correlation_id, set_correlation_id  # noqa: E402
from src.common.logging_config import configure_logging  # noqa: E402

configure_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Secrets Manager is only called in production — elsewhere (local/test defaults),
    # only the environment variable (JWT_SECRET) is used, with no network call.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    yield


# The schema is applied via `alembic upgrade head` in the deployment pipeline — this code
# does not call Base.metadata.create_all here (see docs/architecture/persistence.md).
app = FastAPI(title="Account Service", lifespan=lifespan)

app.include_router(auth_router)
app.include_router(account_router)


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


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
```

The key difference from NestJS is that this code ends with just module-top-level code, with no short, dedicated bootstrap function — there is no step that initializes a DI container at all (no code corresponding to `NestFactory.create(AppModule)`). Calling the `FastAPI()` constructor produces the app instance itself, and everything after that is just calling methods/decorators on that instance. Another notable point is that `validate_env()` is called even before the `FastAPI` import — because fail-fast must run before the app framework itself (see [config.md](config.md), [graceful-shutdown.md](graceful-shutdown.md)).

## Role of each component

| Code | Role |
|------|------|
| `validate_env()` (top of the module) | fail-fast: validates required environment variables (`DatabaseConfig`), calls `sys.exit(1)` on failure. Details: [config.md](config.md) |
| `configure_logging()` | Configures structured JSON logging. Details: [observability.md](observability.md) |
| `FastAPI(title=..., lifespan=lifespan)` | Creates the app instance. `title` becomes the title of the auto-generated OpenAPI docs |
| `lifespan` (`@asynccontextmanager`) | Startup/shutdown hook — before `yield` is startup, after it is shutdown. Currently it only fetches the JWT secret from Secrets Manager in production, and the shutdown block is empty. Details: [graceful-shutdown.md](graceful-shutdown.md) |
| `app.include_router(auth_router)`, `app.include_router(account_router)` | Registers an `APIRouter` — one router per Bounded Context/shared module. Details: [module-pattern.md](module-pattern.md) |
| `correlation_id_middleware` (`@app.middleware("http")`) | Injects a Correlation ID into every request + request logging. Details: [cross-cutting-concerns.md](cross-cutting-concerns.md) |
| `@app.exception_handler(ExcType)` | Converts a domain exception → HTTP response. `AccountNotFoundError` (the concrete type) is registered before `AccountError` (its supertype) so 404 matches before 400. Details: [error-handling.md](error-handling.md) |

## Why registration order matters

For an exception type, FastAPI looks up **the most specific matching handler first** (based on MRO, not registration order). Even so, this repository registers `AccountNotFoundError` right after `AccountError` so the relationship between the two exceptions is clear to the reader — the order itself documents the hierarchy.

## Not yet present — CORS

`main.py` already has `correlation_id_middleware` (`@app.middleware("http")`), but no `CORSMiddleware` yet. If a frontend needs to call it from a different origin, add the following.

```python
# main.py — add this once CORS is needed (not present in examples/ currently)
from fastapi.middleware.cors import CORSMiddleware

app.add_middleware(
    CORSMiddleware,
    allow_origins=os.getenv("CORS_ORIGIN", "*").split(","),
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
```

The Correlation ID/request-logging middleware is covered in [cross-cutting-concerns.md](cross-cutting-concerns.md). See [rate-limiting.md](rate-limiting.md) for the rate-limiting middleware.

## A built-in advantage of FastAPI — no need to set up Swagger by hand

NestJS requires explicitly assembling `DocumentBuilder`/`SwaggerModule.setup()` in `main.ts` to expose API docs (see [nestjs bootstrap.md](../../../nestjs/docs/architecture/bootstrap.md)). FastAPI only needs `FastAPI(title=...)` to be constructed, and it auto-generates the OpenAPI schema from the Pydantic models and route signatures, immediately opening the following two paths with no extra configuration.

| Path | Content |
|------|------|
| `/docs` | Swagger UI |
| `/redoc` | ReDoc |
| `/openapi.json` | The raw OpenAPI schema |

This repository doesn't customize `version` or `description` beyond `title="Account Service"`. JWT authentication (`get_current_user` based on `HTTPBearer` in `src/auth/interface/rest/dependencies.py` — see [authentication.md](authentication.md)) is implemented, and FastAPI automatically adds an "Authorize" button to Swagger UI just by seeing the `Depends(HTTPBearer())` signature on a route function — there's no need, as in NestJS, to attach `@ApiBearerAuth('token')` to every controller or call `DocumentBuilder.addBearerAuth()`.

---

### Related documents

- [graceful-shutdown.md](graceful-shutdown.md) — `lifespan`'s shutdown block, health-check endpoints
- [error-handling.md](error-handling.md) — `@app.exception_handler` registration order and the standard error response
- [module-pattern.md](module-pattern.md) — `APIRouter` registration, `Depends`-based DI
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the middleware pipeline
