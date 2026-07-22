from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.payment_cancelled_integration_event import PaymentCancelledIntegrationEventV1

logger = logging.getLogger(__name__)


class PaymentCancelledEventHandler:
    """Receives the internal Domain Event (PaymentCancelled), converts it into an
    Integration Event for external BCs (payment.cancelled.v1), and loads it into the
    Outbox. The Account BC subscribes to this and executes a compensating credit (deposit)
    — a compensating transaction that reverses the amount already debited.
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "Payment cancelled: payment_id=%s account_id=%s reason=%s",
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
