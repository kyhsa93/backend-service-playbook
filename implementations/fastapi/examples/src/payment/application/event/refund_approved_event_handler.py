from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.refund_approved_integration_event import RefundApprovedIntegrationEventV1

logger = logging.getLogger(__name__)


class RefundApprovedEventHandler:
    """Receives the internal Domain Event (RefundApproved), converts it into an
    Integration Event for external BCs (refund.approved.v1), and loads it into the Outbox.
    The Account BC subscribes to this and executes the refund credit (deposit).
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "Refund approved: refund_id=%s payment_id=%s account_id=%s",
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
