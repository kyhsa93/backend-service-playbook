from dataclasses import dataclass

from ...domain.order import ItemInput, Order
from ...domain.repository import OrderRepository


@dataclass
class CreateOrderCommand:
    user_id: str
    items: list[ItemInput]


class CreateOrderHandler:

    def __init__(self, repo: OrderRepository) -> None:
        self._repo = repo

    async def execute(self, cmd: CreateOrderCommand) -> None:
        order = Order.create(cmd.user_id, cmd.items)
        await self._repo.save(order)
