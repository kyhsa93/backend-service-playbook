from dataclasses import dataclass

from ...domain.errors import OrderNotFoundError
from ...domain.repository import OrderRepository


@dataclass
class GetOrderQuery:
    order_id: str


@dataclass
class GetOrderResult:
    order_id: str
    status: str
    total_amount: int


class GetOrderHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetOrderQuery) -> GetOrderResult:
        order = await self._repo.find_by_id(query.order_id)
        if order is None:
            raise OrderNotFoundError(query.order_id)
        return GetOrderResult(
            order_id=order.order_id,
            status=order.status,
            total_amount=order.total_amount(),
        )
