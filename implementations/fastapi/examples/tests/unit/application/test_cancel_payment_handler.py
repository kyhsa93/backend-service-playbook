from unittest.mock import AsyncMock

import pytest

from src.payment.application.command.cancel_payment_handler import CancelPaymentCommand, CancelPaymentHandler
from src.payment.domain.errors import PaymentCancelRequiresCompletedPaymentError, PaymentNotFoundError
from src.payment.domain.payment import Payment
from src.payment.domain.payment_status import PaymentStatus


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


def make_completed_payment() -> Payment:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=5000)
    payment.complete()
    payment.pull_events()
    return payment


@pytest.mark.asyncio
async def test_execute_a_completed_payment_is_cancelled_and_saved(repo) -> None:
    payment = make_completed_payment()
    repo.find_payments.return_value = ([payment], 1)
    handler = CancelPaymentHandler(repo)

    result = await handler.execute(
        CancelPaymentCommand(requester_id="owner-1", payment_id=payment.payment_id, reason="customer request")
    )

    assert result.status == PaymentStatus.CANCELLED
    repo.save_payment.assert_awaited_once_with(payment)


@pytest.mark.asyncio
async def test_execute_raises_PaymentNotFoundError_when_payment_is_missing(repo) -> None:
    repo.find_payments.return_value = ([], 0)
    handler = CancelPaymentHandler(repo)

    with pytest.raises(PaymentNotFoundError):
        await handler.execute(
            CancelPaymentCommand(requester_id="owner-1", payment_id="non-existent", reason="customer request")
        )

    repo.save_payment.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_a_pending_payment_cannot_be_cancelled(repo) -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=5000)
    repo.find_payments.return_value = ([payment], 1)
    handler = CancelPaymentHandler(repo)

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        await handler.execute(
            CancelPaymentCommand(requester_id="owner-1", payment_id=payment.payment_id, reason="customer request")
        )

    repo.save_payment.assert_not_awaited()
