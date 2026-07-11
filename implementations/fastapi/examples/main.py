import logging
import os
import time
from contextlib import asynccontextmanager

from src.config.validator import validate_env

validate_env()  # 실패 시 여기서 프로세스가 종료된다 — 이후 코드는 실행되지 않음

from fastapi import FastAPI, Request  # noqa: E402
from fastapi.responses import JSONResponse  # noqa: E402
from slowapi import _rate_limit_exceeded_handler  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402
from slowapi.middleware import SlowAPIMiddleware  # noqa: E402

from src.account.domain.errors import AccountError, AccountNotFoundError  # noqa: E402
from src.account.interface.rest.account_router import router as account_router  # noqa: E402
from src.auth.infrastructure.jwt_auth_service import set_jwt_secret  # noqa: E402
from src.auth.interface.rest.auth_router import router as auth_router  # noqa: E402
from src.common.aws_secret_service import AwsSecretService  # noqa: E402
from src.common.correlation import generate_correlation_id, set_correlation_id  # noqa: E402
from src.common.logging_config import configure_logging  # noqa: E402
from src.common.rate_limit import limiter  # noqa: E402

configure_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 프로덕션에서만 Secrets Manager를 호출한다 — 그 외(로컬/테스트 기본값)는
    # 네트워크 호출 없이 환경 변수(JWT_SECRET)만 사용한다.
    if os.getenv("APP_ENV") == "production":
        secret = await AwsSecretService().get_secret("app/jwt")
        set_jwt_secret(secret["secret"])
    yield


# 스키마는 배포 파이프라인에서 `alembic upgrade head`로 적용한다 — 여기서
# Base.metadata.create_all을 호출하지 않는다(docs/architecture/persistence.md 참고).
app = FastAPI(title="Account Service", lifespan=lifespan)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

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


# Rate Limiting — Starlette는 마지막에 등록된 미들웨어가 가장 바깥쪽 레이어가 되므로,
# Correlation ID Middleware(위 데코레이터) 이후에 등록해야 제한 초과 요청을 Correlation ID
# 부여보다 먼저 429로 조기 차단한다(rate-limiting.md "미들웨어 체인에서의 위치" 참고).
app.add_middleware(SlowAPIMiddleware)


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
