import asyncio
import logging
import os
import time
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from src.config.validator import validate_env

validate_env()  # on failure, the process exits right here — no code after this runs

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.exceptions import RequestValidationError  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from prometheus_fastapi_instrumentator import Instrumentator, metrics  # noqa: E402
from slowapi import _rate_limit_exceeded_handler  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402
from slowapi.middleware import SlowAPIMiddleware  # noqa: E402

from src.account.domain.errors import AccountError, AccountNotFoundError  # noqa: E402
from src.account.infrastructure.scheduling.interest_scheduler import start_interest_scheduler  # noqa: E402
from src.account.interface.rest.account_router import router as account_router  # noqa: E402
from src.auth.domain.errors import (  # noqa: E402
    InvalidCredentialsError,
    InvalidTokenError,
    UserIdAlreadyExistsError,
)
from src.auth.infrastructure.jwt_auth_service import set_jwt_secret  # noqa: E402
from src.auth.interface.rest.auth_router import router as auth_router  # noqa: E402
from src.card.domain.errors import CardError, CardNotFoundError, LinkedAccountNotFoundError  # noqa: E402
from src.card.infrastructure.scheduling.statement_scheduler import start_statement_scheduler  # noqa: E402
from src.card.interface.rest.card_router import router as card_router  # noqa: E402
from src.common.aws_secret_service import AwsSecretService  # noqa: E402
from src.common.correlation import generate_correlation_id, set_correlation_id  # noqa: E402
from src.common.error_response import build_error_response  # noqa: E402
from src.common.logging_config import configure_logging  # noqa: E402
from src.common.rate_limit import limiter  # noqa: E402
from src.common.security_headers import apply_security_headers  # noqa: E402
from src.common.tracing import configure_tracing, shutdown_tracing  # noqa: E402
from src.database import SessionLocal, engine  # noqa: E402
from src.outbox.outbox_consumer import OutboxConsumer  # noqa: E402
from src.outbox.outbox_poller import OutboxPoller  # noqa: E402
from src.payment.domain.errors import (  # noqa: E402
    LinkedAccountNotFoundError as PaymentLinkedAccountNotFoundError,
)
from src.payment.domain.errors import LinkedCardNotFoundError, PaymentError, PaymentNotFoundError  # noqa: E402
from src.payment.interface.rest.payment_router import router as payment_router  # noqa: E402
from src.task_queue.task_consumer import TaskConsumer  # noqa: E402
from src.task_queue.task_outbox_poller import TaskOutboxPoller  # noqa: E402

configure_logging()
logger = logging.getLogger(__name__)

app_state = {"is_shutting_down": False}


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    # --- startup (validate_env() has already run above, at module-import time) ---
    # Secrets Manager is only called in production — elsewhere (local/test defaults),
    # only the environment variable (JWT_SECRET) is used, with no network call.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])

    # Starts OutboxPoller (publishing Outbox → SQS) and OutboxConsumer (receiving SQS →
    # EventHandler) as background tasks exactly once at app startup — they are never
    # created fresh per request. A Command Handler never calls either of these
    # (domain-events.md — synchronous draining is forbidden).
    outbox_poller = OutboxPoller(SessionLocal)
    outbox_consumer = OutboxConsumer(SessionLocal)
    # TaskOutboxPoller (publishing Task Outbox → the Task queue) and TaskConsumer
    # (receiving the Task queue → TaskController) are likewise started only once at app
    # startup, following the same principle (scheduling.md). The Scheduler itself
    # (interest_scheduler/statement_scheduler) is started via scheduler.start(), not
    # asyncio.create_task, since APScheduler internally runs the Cron tick on its own
    # event loop.
    task_outbox_poller = TaskOutboxPoller(SessionLocal)
    task_consumer = TaskConsumer(SessionLocal)
    background_tasks = [
        asyncio.create_task(outbox_poller.run_forever()),
        asyncio.create_task(outbox_consumer.run_forever()),
        asyncio.create_task(task_outbox_poller.run_forever()),
        asyncio.create_task(task_consumer.run_forever()),
    ]
    interest_scheduler = start_interest_scheduler(SessionLocal)
    statement_scheduler = start_statement_scheduler(SessionLocal)
    logger.info("app_started")

    yield  # --- requests are handled in between ---

    # --- shutdown (SIGTERM received → uvicorn runs lifespan's shutdown block) ---
    app_state["is_shutting_down"] = True  # flips readiness to 503 immediately
    logger.info("shutdown_initiated")

    # scheduler.shutdown(wait=True) — waits for in-progress jobs to finish before shutting
    # down (graceful-shutdown.md, the APScheduler shutdown convention in fastapi scheduling.md).
    interest_scheduler.shutdown(wait=True)
    statement_scheduler.shutdown(wait=True)

    # Cancels the background tasks and waits for them to fully finish — no dangling task is
    # left behind. An in-progress receive_message (up to WaitTimeSeconds) that
    # OutboxConsumer/TaskConsumer was waiting on is stopped immediately.
    for task in background_tasks:
        task.cancel()
    for task in background_tasks:
        try:
            await task
        except asyncio.CancelledError:
            pass

    try:
        await engine.dispose()  # clean up the DB connection pool
    except Exception:
        logger.exception("shutdown_cleanup_failed")  # don't re-raise even if cleanup fails

    shutdown_tracing()  # flushes any buffered spans (a no-op when no OTLP endpoint is set)

    logger.info("app_stopped")


# The schema is applied via `alembic upgrade head` in the deployment pipeline — this code
# does not call Base.metadata.create_all here (see docs/architecture/persistence.md).
app = FastAPI(
    title="Account Service",
    description="API documentation for the DDD-based Account/Card/Payment/Auth domain example service",
    version="0.1.0",
    lifespan=lifespan,
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.include_router(auth_router)
app.include_router(account_router)
app.include_router(card_router)
app.include_router(payment_router)

# OpenTelemetry auto-instrumentation — must run after every router is registered so
# FastAPIInstrumentor can see the final route table (observability.md).
configure_tracing(app)

# Prometheus — GET /metrics (request count + a duration histogram, both labeled by
# method/handler/status; observability.md). `include_in_schema=False` keeps this purely
# operational endpoint out of the Swagger doc.
Instrumentator().add(metrics.requests()).add(metrics.latency()).instrument(app).expose(
    app, endpoint="/metrics", include_in_schema=False
)


@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}  # always 200 — the process is alive even while shutting down


@app.get("/health/ready")
async def health_ready() -> JSONResponse:
    if app_state["is_shutting_down"]:
        return JSONResponse(status_code=503, content={"status": "shutting_down"})
    return JSONResponse(status_code=200, content={"status": "ready"})


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


# Rate Limiting — since Starlette makes the last-registered middleware the outermost
# layer, this must be registered after the Correlation ID Middleware (the decorator above)
# so a request that exceeds the limit is blocked early with 429 before the Correlation ID
# is even assigned (see "Position in the middleware chain" in rate-limiting.md).
app.add_middleware(SlowAPIMiddleware)


@app.middleware("http")
async def security_headers_middleware(request: Request, call_next):
    # Registered last, so — by the same last-registered-is-outermost rule as above — this
    # wraps every other middleware, including SlowAPIMiddleware's 429 response. That way the
    # security headers land on literally every response this process ever sends, not only the
    # ones that make it past rate limiting.
    response = await call_next(request)
    apply_security_headers(request, response)
    return response


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


@app.exception_handler(CardNotFoundError)
async def card_not_found_handler(request: Request, exc: CardNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(LinkedAccountNotFoundError)
async def linked_account_not_found_handler(request: Request, exc: LinkedAccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(CardError)
async def card_error_handler(request: Request, exc: CardError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


@app.exception_handler(PaymentNotFoundError)
async def payment_not_found_handler(request: Request, exc: PaymentNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(LinkedCardNotFoundError)
async def linked_card_not_found_handler(request: Request, exc: LinkedCardNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(PaymentLinkedAccountNotFoundError)
async def payment_linked_account_not_found_handler(
    request: Request, exc: PaymentLinkedAccountNotFoundError
) -> JSONResponse:
    return JSONResponse(status_code=404, content=build_error_response(404, exc.code.value, str(exc)))


@app.exception_handler(PaymentError)
async def payment_error_handler(request: Request, exc: PaymentError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


@app.exception_handler(InvalidCredentialsError)
async def invalid_credentials_handler(request: Request, exc: InvalidCredentialsError) -> JSONResponse:
    return JSONResponse(status_code=401, content=build_error_response(401, exc.code.value, str(exc)))


@app.exception_handler(InvalidTokenError)
async def invalid_token_handler(request: Request, exc: InvalidTokenError) -> JSONResponse:
    return JSONResponse(status_code=401, content=build_error_response(401, exc.code.value, str(exc)))


@app.exception_handler(UserIdAlreadyExistsError)
async def user_id_already_exists_handler(request: Request, exc: UserIdAlreadyExistsError) -> JSONResponse:
    return JSONResponse(status_code=400, content=build_error_response(400, exc.code.value, str(exc)))


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
    messages = [str(e["msg"]) for e in exc.errors()]
    return JSONResponse(
        status_code=422,
        content=build_error_response(422, "VALIDATION_FAILED", "; ".join(messages)),
    )
