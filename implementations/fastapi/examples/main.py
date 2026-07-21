import asyncio
import logging
import os
import time
from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from src.config.validator import validate_env

validate_env()  # 실패 시 여기서 프로세스가 종료된다 — 이후 코드는 실행되지 않음

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.exceptions import RequestValidationError  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from slowapi import _rate_limit_exceeded_handler  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402
from slowapi.middleware import SlowAPIMiddleware  # noqa: E402

from src.account.domain.errors import AccountError, AccountNotFoundError  # noqa: E402
from src.account.infrastructure.scheduling.interest_scheduler import start_interest_scheduler  # noqa: E402
from src.account.interface.rest.account_router import router as account_router  # noqa: E402
from src.auth.domain.errors import InvalidCredentialsError, UserIdAlreadyExistsError  # noqa: E402
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
    # --- 기동 (validate_env()는 이미 위에서 모듈 임포트 시점에 실행되었다) ---
    # 프로덕션에서만 Secrets Manager를 호출한다 — 그 외(로컬/테스트 기본값)는
    # 네트워크 호출 없이 환경 변수(JWT_SECRET)만 사용한다.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])

    # OutboxPoller(Outbox → SQS 발행)와 OutboxConsumer(SQS → EventHandler 수신)를 앱
    # 기동 시 단 한 번 백그라운드 task로 띄운다 — 요청마다 새로 만들어지지 않는다. Command
    # Handler는 더 이상 이 둘을 호출하지 않는다(domain-events.md — 동기 드레인 금지).
    outbox_poller = OutboxPoller(SessionLocal)
    outbox_consumer = OutboxConsumer(SessionLocal)
    # TaskOutboxPoller(Task Outbox → Task 큐 발행)와 TaskConsumer(Task 큐 → TaskController
    # 수신)도 동일한 원칙으로 앱 기동 시 한 번만 띄운다(scheduling.md). Scheduler
    # 자체(interest_scheduler/statement_scheduler)는 APScheduler가 내부적으로 자기 이벤트
    # 루프에서 Cron tick을 돌리므로 asyncio.create_task가 아니라 scheduler.start()로 기동한다.
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

    yield  # --- 이 사이에 요청을 처리한다 ---

    # --- 종료 (SIGTERM 수신 → uvicorn이 lifespan의 종료 블록을 실행) ---
    app_state["is_shutting_down"] = True  # readiness를 즉시 503으로 전환
    logger.info("shutdown_initiated")

    # scheduler.shutdown(wait=True) — 진행 중인 job이 끝날 때까지 기다린 뒤 종료한다
    # (graceful-shutdown.md, fastapi scheduling.md의 APScheduler 종료 관례).
    interest_scheduler.shutdown(wait=True)
    statement_scheduler.shutdown(wait=True)

    # 백그라운드 task를 취소하고 완전히 끝날 때까지 기다린다 — dangling task를 남기지 않는다.
    # OutboxConsumer/TaskConsumer가 대기 중이던 receive_message(최대 WaitTimeSeconds)는
    # 즉시 중단된다.
    for task in background_tasks:
        task.cancel()
    for task in background_tasks:
        try:
            await task
        except asyncio.CancelledError:
            pass

    try:
        await engine.dispose()  # DB 커넥션 풀 정리
    except Exception:
        logger.exception("shutdown_cleanup_failed")  # 정리 실패해도 예외를 다시 던지지 않는다

    logger.info("app_stopped")


# 스키마는 배포 파이프라인에서 `alembic upgrade head`로 적용한다 — 여기서
# Base.metadata.create_all을 호출하지 않는다(docs/architecture/persistence.md 참고).
app = FastAPI(title="Account Service", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

app.include_router(auth_router)
app.include_router(account_router)
app.include_router(card_router)
app.include_router(payment_router)


@app.get("/health/live")
async def health_live() -> dict:
    return {"status": "ok"}  # 항상 200 — 종료 중에도 프로세스는 살아있다


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


# Rate Limiting — Starlette는 마지막에 등록된 미들웨어가 가장 바깥쪽 레이어가 되므로,
# Correlation ID Middleware(위 데코레이터) 이후에 등록해야 제한 초과 요청을 Correlation ID
# 부여보다 먼저 429로 조기 차단한다(rate-limiting.md "미들웨어 체인에서의 위치" 참고).
app.add_middleware(SlowAPIMiddleware)


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
