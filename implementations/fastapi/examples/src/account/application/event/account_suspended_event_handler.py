from __future__ import annotations

from datetime import datetime

from ....outbox.outbox_writer import OutboxWriter
from ...domain.events import AccountSuspended
from ..integration_event.account_suspended_integration_event import AccountSuspendedIntegrationEventV1
from ..service.notification_service import NotificationService


class AccountSuspendedEventHandler:
    """An Application EventHandler that receives the internal Domain Event
    (AccountSuspended) and performs follow-up processing.

    An EventHandler in application/event/ is the sole exception allowed to use OutboxWriter
    directly — here it converts the event into an Integration Event (account.suspended.v1)
    for external BCs (Card, etc.) and loads it into the Outbox of the same session (an
    Aggregate never creates an Integration Event directly — the conversion point is always
    the EventHandler). This row is published to SQS on the next OutboxPoller tick (up to 1
    second later), after which OutboxConsumer receives it and calls the Card BC's reaction
    handler.
    """

    def __init__(self, notification_service: NotificationService, outbox_writer: OutboxWriter) -> None:
        self._notification_service = notification_service
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        event = AccountSuspended(
            account_id=payload["account_id"],
            email=payload["email"],
            suspended_at=datetime.fromisoformat(payload["suspended_at"]),
        )

        await self._outbox_writer.save_all(
            [
                AccountSuspendedIntegrationEventV1(
                    account_id=event.account_id,
                    suspended_at=event.suspended_at.isoformat(),
                )
            ]
        )

        # The notification is best-effort — NotificationService.notify() swallows every
        # exception internally, so a failure here doesn't affect the Integration Event load
        # above or this handler itself.
        await self._notification_service.notify(event, payload["outbox_event_id"])
