from collections.abc import AsyncGenerator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from src.account.domain.errors import AccountError, AccountNotFoundError
from src.account.infrastructure.persistence.account_repository import Base
from src.account.interface.rest.account_router import router as account_router
from src.database import engine


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncGenerator[None, None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield


app = FastAPI(title="Account Service", lifespan=lifespan)

app.include_router(account_router)


@app.exception_handler(AccountNotFoundError)
async def account_not_found_handler(request: Request, exc: AccountNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(AccountError)
async def account_error_handler(request: Request, exc: AccountError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
