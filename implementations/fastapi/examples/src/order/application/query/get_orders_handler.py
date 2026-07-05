from dataclasses import dataclass

from ...domain.repository import OrderRepository


@dataclass
class GetOrdersQuery:
    page: int
    take: int
    user_id: str | None = None
    status: list[str] | None = None


@dataclass
class OrderSummary:
    order_id: str
    status: str
    total_amount: int


@dataclass
class GetOrdersResult:
    orders: list[OrderSummary]
    total_count: int


class GetOrdersHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, query: GetOrdersQuery) -> GetOrdersResult:
        orders, total = await self._repo.find_all(
            page=query.page,
            take=query.take,
            user_id=query.user_id,
            status=query.status,
        )
        return GetOrdersResult(
            orders=[
                OrderSummary(order_id=o.order_id, status=o.status, total_amount=o.total_amount())
                for o in orders
            ],
            total_count=total,
        )
