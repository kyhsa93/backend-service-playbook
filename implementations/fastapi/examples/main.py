from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from src.account.domain.errors import AccountError, AccountNotFoundError
from src.account.interface.rest.account_router import router as account_router
from src.auth.interface.rest.auth_router import router as auth_router

# 스키마는 배포 파이프라인에서 `alembic upgrade head`로 적용한다 — 여기서
# Base.metadata.create_all을 호출하지 않는다(docs/architecture/persistence.md 참고).
app = FastAPI(title="Account Service")

app.include_router(auth_router)
app.include_router(account_router)


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
