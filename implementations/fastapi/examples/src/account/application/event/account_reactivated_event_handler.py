from __future__ import annotations

from datetime import datetime

from ...domain.events import AccountReactivated
from ..service.notification_service import NotificationService


class AccountReactivatedEventHandler:
    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = AccountReactivated(
            account_id=payload["account_id"],
            email=payload["email"],
            reactivated_at=datetime.fromisoformat(payload["reactivated_at"]),
        )
        await self._notification_service.notify(event)
