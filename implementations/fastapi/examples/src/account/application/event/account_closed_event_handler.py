from __future__ import annotations

from datetime import datetime

from ...domain.events import AccountClosed
from ..service.notification_service import NotificationService


class AccountClosedEventHandler:

    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = AccountClosed(
            account_id=payload["account_id"],
            email=payload["email"],
            closed_at=datetime.fromisoformat(payload["closed_at"]),
        )
        await self._notification_service.notify(event)
