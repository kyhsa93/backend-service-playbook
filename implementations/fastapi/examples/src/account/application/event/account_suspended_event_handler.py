from __future__ import annotations

from datetime import datetime

from ....outbox.outbox_writer import OutboxWriter
from ...domain.events import AccountSuspended
from ..integration_event.account_suspended_integration_event import AccountSuspendedIntegrationEventV1
from ..service.notification_service import NotificationService


class AccountSuspendedEventHandler:
    """내부 Domain Event(AccountSuspended)를 수신해 후속 처리를 수행하는 Application EventHandler.

    application/event/의 EventHandler는 OutboxWriter를 직접 사용할 수 있는 유일한 예외로,
    여기서 외부 BC(Card 등)용 Integration Event(account.suspended.v1)로 변환해 같은 세션의
    Outbox에 적재한다(Aggregate가 Integration Event를 직접 만들지 않는다 — 변환 지점은 항상
    EventHandler다). 이 행은 다음 OutboxPoller tick(최대 1초 뒤)에 SQS로 발행되고, 그 뒤
    OutboxConsumer가 수신해 Card BC의 반응 핸들러를 호출한다.
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

        # 알림은 best-effort다 — NotificationService.notify()가 내부에서 모든 예외를 삼키므로
        # 여기서 실패해도 위 Integration Event 적재나 이 핸들러 자체는 영향받지 않는다.
        await self._notification_service.notify(event, payload["outbox_event_id"])
