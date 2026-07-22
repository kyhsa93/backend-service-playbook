from __future__ import annotations

from datetime import datetime

from ....outbox.outbox_writer import OutboxWriter
from ...domain.events import AccountClosed
from ..integration_event.account_closed_integration_event import AccountClosedIntegrationEventV1
from ..service.notification_service import NotificationService


class AccountClosedEventHandler:
    """An Application EventHandler that receives the internal Domain Event (AccountClosed)
    and performs follow-up processing.

    An EventHandler in application/event/ is the sole exception allowed to use OutboxWriter
    directly — here it converts the event into an Integration Event (account.closed.v1) for
    external BCs (Card, etc.) and loads it into the Outbox of the same session (the
    conversion point is always the EventHandler).
    """

    def __init__(self, notification_service: NotificationService, outbox_writer: OutboxWriter) -> None:
        self._notification_service = notification_service
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        event = AccountClosed(
            account_id=payload["account_id"],
            email=payload["email"],
            closed_at=datetime.fromisoformat(payload["closed_at"]),
        )

        await self._outbox_writer.save_all(
            [
                AccountClosedIntegrationEventV1(
                    account_id=event.account_id,
                    closed_at=event.closed_at.isoformat(),
                )
            ]
        )

        await self._notification_service.notify(event, payload["outbox_event_id"])
