import pytest

from src.payment.domain.errors import (
    PaymentCancelRequiresCompletedPaymentError,
    PaymentCompleteRequiresPendingPaymentError,
    PaymentFailRequiresPendingPaymentError,
)
from src.payment.domain.payment import Payment
from src.payment.domain.payment_status import PaymentStatus


def make_pending_payment() -> Payment:
    return Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=10000)


def test_create_결제는_PENDING_상태로_생성된다() -> None:
    payment = make_pending_payment()

    assert payment.status == PaymentStatus.PENDING
    assert payment.card_id == "card-1"
    assert payment.account_id == "account-1"
    assert payment.owner_id == "owner-1"
    assert payment.amount == 10000
    assert payment.payment_id
    assert payment.created_at
    assert payment.pull_events() == []


def test_complete_대기중인_결제는_완료되고_PaymentCompleted_이벤트가_발행된다() -> None:
    payment = make_pending_payment()

    payment.complete()

    assert payment.status == PaymentStatus.COMPLETED
    events = payment.pull_events()
    assert len(events) == 1
    event = events[0]
    assert event.payment_id == payment.payment_id
    assert event.card_id == "card-1"
    assert event.account_id == "account-1"
    assert event.owner_id == "owner-1"
    assert event.amount == 10000


def test_complete_이미_완료된_결제는_다시_완료할_수_없다() -> None:
    payment = make_pending_payment()
    payment.complete()

    with pytest.raises(PaymentCompleteRequiresPendingPaymentError):
        payment.complete()


def test_fail_대기중인_결제는_실패로_전환된다() -> None:
    payment = make_pending_payment()

    payment.fail("gateway error")

    assert payment.status == PaymentStatus.FAILED
    # Payment.fail() is a domain method not yet wired to any Command, so it doesn't publish
    # an event (unlike cancel()).
    assert payment.pull_events() == []


def test_fail_완료된_결제는_실패로_전환할_수_없다() -> None:
    payment = make_pending_payment()
    payment.complete()

    with pytest.raises(PaymentFailRequiresPendingPaymentError):
        payment.fail("gateway error")


def test_cancel_완료된_결제는_취소되고_PaymentCancelled_이벤트가_발행된다() -> None:
    payment = make_pending_payment()
    payment.complete()
    payment.pull_events()

    payment.cancel("customer request")

    assert payment.status == PaymentStatus.CANCELLED
    events = payment.pull_events()
    assert len(events) == 1
    event = events[0]
    assert event.payment_id == payment.payment_id
    assert event.account_id == "account-1"
    assert event.owner_id == "owner-1"
    assert event.amount == 10000
    assert event.reason == "customer request"


def test_cancel_대기중인_결제는_취소할_수_없다() -> None:
    payment = make_pending_payment()

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        payment.cancel("customer request")


def test_cancel_이미_취소된_결제는_다시_취소할_수_없다() -> None:
    payment = make_pending_payment()
    payment.complete()
    payment.cancel("customer request")

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        payment.cancel("duplicate cancellation")
