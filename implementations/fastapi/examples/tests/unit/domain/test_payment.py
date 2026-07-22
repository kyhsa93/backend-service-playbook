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


def test_create_a_payment_is_created_in_PENDING_status() -> None:
    payment = make_pending_payment()

    assert payment.status == PaymentStatus.PENDING
    assert payment.card_id == "card-1"
    assert payment.account_id == "account-1"
    assert payment.owner_id == "owner-1"
    assert payment.amount == 10000
    assert payment.payment_id
    assert payment.created_at
    assert payment.pull_events() == []


def test_complete_a_pending_payment_completes_and_publishes_a_PaymentCompleted_event() -> None:
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


def test_complete_an_already_completed_payment_cannot_be_completed_again() -> None:
    payment = make_pending_payment()
    payment.complete()

    with pytest.raises(PaymentCompleteRequiresPendingPaymentError):
        payment.complete()


def test_fail_a_pending_payment_transitions_to_failed() -> None:
    payment = make_pending_payment()

    payment.fail("gateway error")

    assert payment.status == PaymentStatus.FAILED
    # Payment.fail() is a domain method not yet wired to any Command, so it doesn't publish
    # an event (unlike cancel()).
    assert payment.pull_events() == []


def test_fail_a_completed_payment_cannot_transition_to_failed() -> None:
    payment = make_pending_payment()
    payment.complete()

    with pytest.raises(PaymentFailRequiresPendingPaymentError):
        payment.fail("gateway error")


def test_cancel_a_completed_payment_is_cancelled_and_publishes_a_PaymentCancelled_event() -> None:
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


def test_cancel_a_pending_payment_cannot_be_cancelled() -> None:
    payment = make_pending_payment()

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        payment.cancel("customer request")


def test_cancel_an_already_cancelled_payment_cannot_be_cancelled_again() -> None:
    payment = make_pending_payment()
    payment.complete()
    payment.cancel("customer request")

    with pytest.raises(PaymentCancelRequiresCompletedPaymentError):
        payment.cancel("duplicate cancellation")
