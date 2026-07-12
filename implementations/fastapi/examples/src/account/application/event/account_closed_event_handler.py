from __future__ import annotations

from datetime import datetime

from ....outbox.outbox_writer import OutboxWriter
from ...domain.events import AccountClosed
from ..integration_event.account_closed_integration_event import AccountClosedIntegrationEventV1
from ..service.notification_service import NotificationService


class AccountClosedEventHandler:
    """내부 Domain Event(AccountClosed)를 수신해 후속 처리를 수행하는 Application EventHandler.

    application/event/의 EventHandler는 OutboxWriter를 직접 사용할 수 있는 유일한 예외로,
    여기서 외부 BC(Card 등)용 Integration Event(account.closed.v1)로 변환해 같은 세션의
    Outbox에 적재한다(변환 지점은 항상 EventHandler다).
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

        await self._outbox_writer.save_all([
            AccountClosedIntegrationEventV1(
                account_id=event.account_id,
                closed_at=event.closed_at.isoformat(),
            )
        ])

        await self._notification_service.notify(event)
