from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from src.order.domain.errors import (
    OrderAlreadyCancelledError,
    OrderNotFoundError,
    OrderPaidNotCancellableError,
)
from src.order.interface.rest.order_router import router as order_router

app = FastAPI(title="Order Service")

app.include_router(order_router)


@app.exception_handler(OrderNotFoundError)
async def order_not_found_handler(request: Request, exc: OrderNotFoundError) -> JSONResponse:
    return JSONResponse(status_code=404, content={"message": str(exc)})


@app.exception_handler(OrderAlreadyCancelledError)
async def order_already_cancelled_handler(request: Request, exc: OrderAlreadyCancelledError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})


@app.exception_handler(OrderPaidNotCancellableError)
async def order_paid_handler(request: Request, exc: OrderPaidNotCancellableError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"message": str(exc)})
