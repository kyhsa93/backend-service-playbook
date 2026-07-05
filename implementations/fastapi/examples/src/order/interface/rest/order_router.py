from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from ...application.command.cancel_order_handler import CancelOrderCommand, CancelOrderHandler
from ...application.command.create_order_handler import CreateOrderCommand, CreateOrderHandler
from ...application.query.get_order_handler import GetOrderHandler, GetOrderQuery
from ...application.query.get_orders_handler import GetOrdersHandler, GetOrdersQuery
from ...domain.order import ItemInput
from ...infrastructure.persistence.order_repository import SqlAlchemyOrderRepository
from .schemas import (
    CancelOrderRequest,
    CreateOrderRequest,
    GetOrderResponse,
    GetOrdersResponse,
)

router = APIRouter(prefix="/orders", tags=["Order"])


def _repo(session: AsyncSession = Depends(lambda: None)) -> SqlAlchemyOrderRepository:
    return SqlAlchemyOrderRepository(session)


@router.get("/", response_model=GetOrdersResponse)
async def get_orders(
    page: int = 0,
    take: int = 20,
    user_id: str | None = None,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> GetOrdersResponse:
    result = await GetOrdersHandler(repo).execute(
        GetOrdersQuery(page=page, take=take, user_id=user_id)
    )
    return GetOrdersResponse(
        orders=[{"order_id": o.order_id, "status": o.status, "total_amount": o.total_amount} for o in result.orders],
        total_count=result.total_count,
    )


@router.get("/{order_id}", response_model=GetOrderResponse)
async def get_order(
    order_id: str,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> GetOrderResponse:
    result = await GetOrderHandler(repo).execute(GetOrderQuery(order_id=order_id))
    return GetOrderResponse(order_id=result.order_id, status=result.status, total_amount=result.total_amount)


@router.post("/", status_code=201)
async def create_order(
    body: CreateOrderRequest,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> None:
    items = [ItemInput(i.item_id, i.name, i.price, i.quantity) for i in body.items]
    await CreateOrderHandler(repo).execute(CreateOrderCommand(user_id=body.user_id, items=items))


@router.post("/{order_id}/cancel", status_code=204)
async def cancel_order(
    order_id: str,
    body: CancelOrderRequest,
    repo: SqlAlchemyOrderRepository = Depends(_repo),
) -> None:
    await CancelOrderHandler(repo).execute(CancelOrderCommand(order_id=order_id, reason=body.reason))
