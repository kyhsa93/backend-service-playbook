from __future__ import annotations

from datetime import datetime

from ...domain.events import AccountCreated
from ..service.notification_service import NotificationService


class AccountCreatedEventHandler:
    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = AccountCreated(
            account_id=payload["account_id"],
            owner_id=payload["owner_id"],
            currency=payload["currency"],
            email=payload["email"],
            created_at=datetime.fromisoformat(payload["created_at"]),
        )
        await self._notification_service.notify(event, payload["outbox_event_id"])
