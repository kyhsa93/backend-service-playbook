from unittest.mock import AsyncMock

import pytest

from src.payment.application.command.request_refund_handler import RequestRefundCommand, RequestRefundHandler
from src.payment.domain.errors import PaymentNotFoundError
from src.payment.domain.payment import Payment
from src.payment.domain.refund_status import RefundStatus


@pytest.fixture
def payment_repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def refund_repo() -> AsyncMock:
    return AsyncMock()


def make_completed_payment(amount: int = 10000) -> Payment:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=amount)
    payment.complete()
    payment.pull_events()
    return payment


@pytest.mark.asyncio
async def test_execute_a_refund_request_at_or_under_the_payment_amount_is_approved_and_saved(
    payment_repo, refund_repo
) -> None:
    payment = make_completed_payment(amount=10000)
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo)

    refund = await handler.execute(
        RequestRefundCommand(
            requester_id="owner-1", payment_id=payment.payment_id, amount=10000, reason="personal change of mind"
        )
    )

    assert refund.status == RefundStatus.APPROVED
    refund_repo.save_refund.assert_awaited_once_with(refund)


@pytest.mark.asyncio
async def test_execute_a_refund_request_exceeding_the_payment_amount_is_rejected_and_saved_without_raising(
    payment_repo, refund_repo
) -> None:
    payment = make_completed_payment(amount=10000)
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo)

    refund = await handler.execute(
        RequestRefundCommand(
            requester_id="owner-1", payment_id=payment.payment_id, amount=10001, reason="personal change of mind"
        )
    )

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "The refund amount cannot exceed the payment amount."
    refund_repo.save_refund.assert_awaited_once_with(refund)


@pytest.mark.asyncio
async def test_execute_a_refund_request_for_a_non_completed_payment_is_rejected(payment_repo, refund_repo) -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=10000)  # PENDING
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo)

    refund = await handler.execute(
        RequestRefundCommand(
            requester_id="owner-1", payment_id=payment.payment_id, amount=5000, reason="personal change of mind"
        )
    )

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "A refund can only be requested for a completed payment."


@pytest.mark.asyncio
async def test_execute_raises_PaymentNotFoundError_when_payment_is_missing(payment_repo, refund_repo) -> None:
    payment_repo.find_payments.return_value = ([], 0)
    handler = RequestRefundHandler(payment_repo, refund_repo)

    with pytest.raises(PaymentNotFoundError):
        await handler.execute(
            RequestRefundCommand(
                requester_id="owner-1", payment_id="non-existent", amount=5000, reason="personal change of mind"
            )
        )

    refund_repo.save_refund.assert_not_awaited()
