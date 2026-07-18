from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.refund_approved_integration_event import RefundApprovedIntegrationEventV1

logger = logging.getLogger(__name__)


class RefundApprovedEventHandler:
    """내부 Domain Event(RefundApproved)를 수신해 외부 BC용 Integration Event
    (refund.approved.v1)로 변환해 Outbox에 적재한다. Account BC가 이를 구독해
    환불 크레딧(deposit)을 실행한다.
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "환불 승인됨: refund_id=%s payment_id=%s account_id=%s",
            payload["refund_id"],
            payload["payment_id"],
            payload["account_id"],
        )
        await self._outbox_writer.save_all(
            [
                RefundApprovedIntegrationEventV1(
                    refund_id=payload["refund_id"],
                    payment_id=payload["payment_id"],
                    account_id=payload["account_id"],
                    amount=payload["amount"],
                    approved_at=payload["approved_at"],
                )
            ]
        )
