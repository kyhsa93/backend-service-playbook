from dataclasses import dataclass

from ...domain.errors import OrderNotFoundError
from ...domain.repository import OrderRepository


@dataclass
class CancelOrderCommand:
    order_id: str
    reason: str


class CancelOrderHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CancelOrderCommand) -> None:
        order = await self._repo.find_by_id(cmd.order_id)
        if order is None:
            raise OrderNotFoundError(cmd.order_id)
        order.cancel(cmd.reason)
        await self._repo.save(order)
