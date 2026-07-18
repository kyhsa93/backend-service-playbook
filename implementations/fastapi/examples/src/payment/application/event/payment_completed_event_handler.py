from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.payment_completed_integration_event import PaymentCompletedIntegrationEventV1

logger = logging.getLogger(__name__)


class PaymentCompletedEventHandler:
    """내부 Domain Event(PaymentCompleted)를 수신해 외부 BC용 Integration Event
    (payment.completed.v1)로 변환해 Outbox에 적재하는 Application EventHandler.
    Account BC가 이 Integration Event를 구독해 실제 차감(withdraw)을 수행한다.
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "결제 완료됨: payment_id=%s account_id=%s amount=%s",
            payload["payment_id"],
            payload["account_id"],
            payload["amount"],
        )
        await self._outbox_writer.save_all(
            [
                PaymentCompletedIntegrationEventV1(
                    payment_id=payload["payment_id"],
                    account_id=payload["account_id"],
                    amount=payload["amount"],
                    completed_at=payload["completed_at"],
                )
            ]
        )
