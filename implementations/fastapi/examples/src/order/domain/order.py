from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import datetime
from typing import Literal

from .errors import OrderAlreadyCancelledError, OrderEmptyItemsError, OrderPaidNotCancellableError
from .order_cancelled import OrderCancelled
from .order_item import OrderItem

OrderStatus = Literal["pending", "paid", "cancelled"]


@dataclass
class ItemInput:
    item_id: int
    name: str
    price: int
    quantity: int


class Order:
    def __init__(
        self,
        order_id: str,
        user_id: str,
        items: list[OrderItem],
        status: OrderStatus,
    ) -> None:
        self.order_id = order_id
        self.user_id = user_id
        self.items = items
        self.status: OrderStatus = status
        self._events: list[OrderCancelled] = []

    @classmethod
    def create(cls, user_id: str, inputs: list[ItemInput]) -> Order:
        if not inputs:
            raise OrderEmptyItemsError()
        items = [OrderItem(i.item_id, i.name, i.price, i.quantity) for i in inputs]
        return cls(
            order_id=str(uuid.uuid4()),
            user_id=user_id,
            items=items,
            status="pending",
        )

    def cancel(self, reason: str) -> None:
        if self.status == "cancelled":
            raise OrderAlreadyCancelledError()
        if self.status == "paid":
            raise OrderPaidNotCancellableError()
        self.status = "cancelled"
        self._events.append(OrderCancelled(
            order_id=self.order_id,
            reason=reason,
            cancelled_at=datetime.utcnow(),
        ))

    def total_amount(self) -> int:
        return sum(item.price * item.quantity for item in self.items)

    def pull_events(self) -> list[OrderCancelled]:
        events, self._events = self._events, []
        return events
