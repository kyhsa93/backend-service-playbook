from __future__ import annotations

from datetime import datetime

from ...domain.events import MoneyWithdrawn
from ...domain.money import Money
from ..service.notification_service import NotificationService


class MoneyWithdrawnEventHandler:
    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = MoneyWithdrawn(
            account_id=payload["account_id"],
            transaction_id=payload["transaction_id"],
            email=payload["email"],
            amount=Money(**payload["amount"]),
            balance_after=Money(**payload["balance_after"]),
            created_at=datetime.fromisoformat(payload["created_at"]),
        )
        await self._notification_service.notify(event)
