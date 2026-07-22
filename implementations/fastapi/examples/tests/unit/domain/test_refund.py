import pytest

from src.payment.domain.errors import (
    RefundApproveRequiresRequestedRefundError,
    RefundCompleteRequiresApprovedRefundError,
    RefundRejectRequiresRequestedRefundError,
)
from src.payment.domain.refund import Refund
from src.payment.domain.refund_status import RefundStatus


def make_requested_refund(amount: int = 5000) -> Refund:
    return Refund.create(payment_id="payment-1", amount=amount, reason="personal change of mind")


def test_create_a_refund_is_created_in_REQUESTED_status() -> None:
    refund = make_requested_refund()

    assert refund.status == RefundStatus.REQUESTED
    assert refund.payment_id == "payment-1"
    assert refund.amount == 5000
    assert refund.reason == "personal change of mind"
    assert refund.refund_id
    assert refund.decision_note is None
    assert refund.pull_events() == []


def test_approve_a_requested_refund_is_approved_and_publishes_a_RefundApproved_event() -> None:
    refund = make_requested_refund()

    refund.approve(account_id="account-1", owner_id="owner-1")

    assert refund.status == RefundStatus.APPROVED
    assert refund.decision_note == "The refund has been approved."
    events = refund.pull_events()
    assert len(events) == 1
    event = events[0]
    assert event.refund_id == refund.refund_id
    assert event.payment_id == "payment-1"
    assert event.account_id == "account-1"
    assert event.owner_id == "owner-1"
    assert event.amount == 5000


def test_approve_an_already_approved_refund_cannot_be_approved_again() -> None:
    refund = make_requested_refund()
    refund.approve(account_id="account-1", owner_id="owner-1")

    with pytest.raises(RefundApproveRequiresRequestedRefundError):
        refund.approve(account_id="account-1", owner_id="owner-1")


def test_reject_a_requested_refund_is_rejected_and_the_reason_is_recorded_in_decision_note() -> None:
    refund = make_requested_refund()

    refund.reject("The refund amount exceeds the payment amount.")

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "The refund amount exceeds the payment amount."
    assert refund.pull_events() == []


def test_reject_an_already_rejected_refund_cannot_be_rejected_again() -> None:
    refund = make_requested_refund()
    refund.reject("reason")

    with pytest.raises(RefundRejectRequiresRequestedRefundError):
        refund.reject("another reason")


def test_complete_an_approved_refund_transitions_to_completed() -> None:
    refund = make_requested_refund()
    refund.approve(account_id="account-1", owner_id="owner-1")

    refund.complete()

    assert refund.status == RefundStatus.COMPLETED


def test_complete_a_requested_refund_cannot_transition_to_completed() -> None:
    refund = make_requested_refund()

    with pytest.raises(RefundCompleteRequiresApprovedRefundError):
        refund.complete()
