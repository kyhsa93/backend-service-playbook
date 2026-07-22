from __future__ import annotations

import logging

from ...application.command.deposit_by_payment_handler import DepositByPaymentCommand, DepositByPaymentHandler
from ...application.command.withdraw_by_payment_handler import WithdrawByPaymentCommand, WithdrawByPaymentHandler
from ...domain.repository import AccountRepository

logger = logging.getLogger(__name__)


class AccountIntegrationEventController:
    """An Interface input adapter that receives an Integration Event published by an
    external BC (Payment).

    The same location and role as the Card BC's card_integration_event_controller.py
    (which subscribes to Account events) — just as Account never queries Payment via an
    Adapter, Payment never references Account directly either. It only calls its own
    domain's use case (a Command Handler), and lets exceptions propagate as-is so
    OutboxConsumer doesn't delete the message and instead retries it (→ DLQ).
    """

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def on_payment_completed(self, payload: dict) -> None:
        logger.info(
            "Received payment.completed.v1: payment_id=%s account_id=%s", payload["payment_id"], payload["account_id"]
        )
        await WithdrawByPaymentHandler(self._repo).execute(
            WithdrawByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["payment_id"]
            )
        )

    async def on_payment_cancelled(self, payload: dict) -> None:
        logger.info(
            "Received payment.cancelled.v1: payment_id=%s account_id=%s", payload["payment_id"], payload["account_id"]
        )
        await DepositByPaymentHandler(self._repo).execute(
            DepositByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["payment_id"]
            )
        )

    async def on_refund_approved(self, payload: dict) -> None:
        logger.info(
            "Received refund.approved.v1: refund_id=%s account_id=%s", payload["refund_id"], payload["account_id"]
        )
        await DepositByPaymentHandler(self._repo).execute(
            DepositByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["refund_id"]
            )
        )
