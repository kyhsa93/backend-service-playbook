from __future__ import annotations

import logging

from ...application.command.deposit_by_payment_handler import DepositByPaymentCommand, DepositByPaymentHandler
from ...application.command.withdraw_by_payment_handler import WithdrawByPaymentCommand, WithdrawByPaymentHandler
from ...domain.repository import AccountRepository

logger = logging.getLogger(__name__)


class AccountIntegrationEventController:
    """외부 BC(Payment)가 발행한 Integration Event를 수신하는 Interface 입력 어댑터.

    Card BC의 card_integration_event_controller.py(Account 이벤트 구독)와 동일한 위치·
    역할이다 — Account가 Payment를 Adapter로 조회하지 않는 것처럼, Payment도 Account를
    직접 참조하지 않는다. 자기 도메인의 유스케이스(Command Handler)만 호출하고, 예외는
    그대로 전파해 OutboxConsumer가 메시지를 삭제하지 않고 재시도(→ DLQ)를 담당하게 한다.
    """

    def __init__(self, repo: AccountRepository) -> None:
        self._repo = repo

    async def on_payment_completed(self, payload: dict) -> None:
        logger.info(
            "payment.completed.v1 수신: payment_id=%s account_id=%s", payload["payment_id"], payload["account_id"]
        )
        await WithdrawByPaymentHandler(self._repo).execute(
            WithdrawByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["payment_id"]
            )
        )

    async def on_payment_cancelled(self, payload: dict) -> None:
        logger.info(
            "payment.cancelled.v1 수신: payment_id=%s account_id=%s", payload["payment_id"], payload["account_id"]
        )
        await DepositByPaymentHandler(self._repo).execute(
            DepositByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["payment_id"]
            )
        )

    async def on_refund_approved(self, payload: dict) -> None:
        logger.info("refund.approved.v1 수신: refund_id=%s account_id=%s", payload["refund_id"], payload["account_id"])
        await DepositByPaymentHandler(self._repo).execute(
            DepositByPaymentCommand(
                account_id=payload["account_id"], amount=payload["amount"], reference_id=payload["refund_id"]
            )
        )
