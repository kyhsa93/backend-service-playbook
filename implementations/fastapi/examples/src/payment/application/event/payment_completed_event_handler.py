from __future__ import annotations

import logging

from ....outbox.outbox_writer import OutboxWriter
from ..integration_event.payment_completed_integration_event import PaymentCompletedIntegrationEventV1

logger = logging.getLogger(__name__)


class PaymentCompletedEventHandler:
    """An Application EventHandler that receives the internal Domain Event
    (PaymentCompleted), converts it into an Integration Event for external BCs
    (payment.completed.v1), and loads it into the Outbox. The Account BC subscribes to this
    Integration Event and performs the actual debit (withdraw).
    """

    def __init__(self, outbox_writer: OutboxWriter) -> None:
        self._outbox_writer = outbox_writer

    async def handle(self, payload: dict) -> None:
        logger.info(
            "Payment completed: payment_id=%s account_id=%s amount=%s",
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
