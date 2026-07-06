from __future__ import annotations

from datetime import datetime

from ...domain.events import AccountSuspended
from ..service.notification_service import NotificationService


class AccountSuspendedEventHandler:

    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = AccountSuspended(
            account_id=payload["account_id"],
            email=payload["email"],
            suspended_at=datetime.fromisoformat(payload["suspended_at"]),
        )
        await self._notification_service.notify(event)
