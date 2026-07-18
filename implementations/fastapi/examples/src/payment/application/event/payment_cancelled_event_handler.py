from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.payment_cancelled_integration_event import PaymentCancelledIntegrationEventV1

logger = logging.getLogger(__name__)


class PaymentCancelledEventHandler:
    """내부 Domain Event(PaymentCancelled)를 수신해 외부 BC용 Integration Event
    (payment.cancelled.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해
    보상 크레딧(deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "결제 취소됨: payment_id=%s account_id=%s reason=%s",
            payload["payment_id"],
            payload["account_id"],
            payload["reason"],
        )
        await self._outbox_writer.save_all(
            [
                PaymentCancelledIntegrationEventV1(
                    payment_id=payload["payment_id"],
                    account_id=payload["account_id"],
                    amount=payload["amount"],
                    cancelled_at=payload["cancelled_at"],
                )
            ]
        )
