from __future__ import annotations

from datetime import datetime

from ...domain.events import CardStatementSent
from ..service.notification_service import NotificationService


class CardStatementSentEventHandler:
    def __init__(self, notification_service: NotificationService) -> None:
        self._notification_service = notification_service

    async def handle(self, payload: dict) -> None:
        event = CardStatementSent(
            card_id=payload["card_id"],
            account_id=payload["account_id"],
            owner_id=payload["owner_id"],
            email=payload["email"],
            period=payload["period"],
            payment_count=payload["payment_count"],
            total_amount=payload["total_amount"],
            sent_at=datetime.fromisoformat(payload["sent_at"]),
        )
        await self._notification_service.notify(event, payload["outbox_event_id"])
