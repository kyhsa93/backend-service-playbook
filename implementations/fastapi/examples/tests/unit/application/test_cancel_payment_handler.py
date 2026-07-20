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
async def test_execute_완료된_결제는_취소되고_저장된다(repo) -> None:
    payment = make_completed_payment()
    repo.find_payments.return_value = ([payment], 1)
    handler = CancelPaymentHandler(repo)

    result = await handler.execute(
        CancelPaymentCommand(requester_id="owner-1", payment_id=payment.payment_id, reason="고객 요청")
    )

    assert result.status == PaymentStatus.CANCELLED
    repo.save_payment.assert_awaited_once_with(payment)


@pytest.mark.asyncio
async def test_execute_결제가_없으면_PaymentNotFoundError(repo) -> None:
    repo.find_payments.return_value = ([], 0)
    handler = CancelPaymentHandler(repo)

    with pytest.raises(PaymentNotFoundError):
        await handler.execute(
            CancelPaymentCommand(requester_id="owner-1", payment_id="non-existent", reason="고객 요청")
        )

    repo.save_payment.assert_not_awaited()


@pytest.mark.asyncio
async def test_execute_대기중인_결제는_취소할_수_없다(repo) -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=5000)
    repo.find_payments.return_value = ([payment], 1)
    handler = CancelPaymentHandler(repo)

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        await handler.execute(
            CancelPaymentCommand(requester_id="owner-1", payment_id=payment.payment_id, reason="고객 요청")
        )

    repo.save_payment.assert_not_awaited()
