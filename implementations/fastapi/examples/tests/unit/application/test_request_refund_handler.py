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


@pytest.fixture
def outbox_relay() -> AsyncMock:
    return AsyncMock()


def make_completed_payment(amount: int = 10000) -> Payment:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=amount)
    payment.complete()
    payment.pull_events()
    return payment


@pytest.mark.asyncio
async def test_execute_결제금액_이하_환불요청은_승인되어_저장된다(payment_repo, refund_repo, outbox_relay) -> None:
    payment = make_completed_payment(amount=10000)
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo, outbox_relay)

    refund = await handler.execute(
        RequestRefundCommand(requester_id="owner-1", payment_id=payment.payment_id, amount=10000, reason="단순 변심")
    )

    assert refund.status == RefundStatus.APPROVED
    refund_repo.save.assert_awaited_once_with(refund)
    outbox_relay.process_pending.assert_awaited_once()


@pytest.mark.asyncio
async def test_execute_결제금액을_초과하는_환불요청은_거부되어_저장되지만_예외는_던지지_않는다(
    payment_repo, refund_repo, outbox_relay
) -> None:
    payment = make_completed_payment(amount=10000)
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo, outbox_relay)

    refund = await handler.execute(
        RequestRefundCommand(requester_id="owner-1", payment_id=payment.payment_id, amount=10001, reason="단순 변심")
    )

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "환불 금액은 결제 금액을 초과할 수 없습니다."
    refund_repo.save.assert_awaited_once_with(refund)
    outbox_relay.process_pending.assert_awaited_once()


@pytest.mark.asyncio
async def test_execute_완료되지_않은_결제에_대한_환불요청은_거부된다(payment_repo, refund_repo, outbox_relay) -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=10000)  # PENDING
    payment_repo.find_payments.return_value = ([payment], 1)
    handler = RequestRefundHandler(payment_repo, refund_repo, outbox_relay)

    refund = await handler.execute(
        RequestRefundCommand(requester_id="owner-1", payment_id=payment.payment_id, amount=5000, reason="단순 변심")
    )

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "완료된 결제에 대해서만 환불을 요청할 수 있습니다."


@pytest.mark.asyncio
async def test_execute_결제가_없으면_PaymentNotFoundError(payment_repo, refund_repo, outbox_relay) -> None:
    payment_repo.find_payments.return_value = ([], 0)
    handler = RequestRefundHandler(payment_repo, refund_repo, outbox_relay)

    with pytest.raises(PaymentNotFoundError):
        await handler.execute(
            RequestRefundCommand(requester_id="owner-1", payment_id="non-existent", amount=5000, reason="단순 변심")
        )

    refund_repo.save.assert_not_awaited()
