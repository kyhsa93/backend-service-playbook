import pytest

from src.payment.domain.errors import (
    RefundApproveRequiresRequestedRefundError,
    RefundCompleteRequiresApprovedRefundError,
    RefundRejectRequiresRequestedRefundError,
)
from src.payment.domain.refund import Refund
from src.payment.domain.refund_status import RefundStatus


def make_requested_refund(amount: int = 5000) -> Refund:
    return Refund.create(payment_id="payment-1", amount=amount, reason="단순 변심")


def test_create_환불은_REQUESTED_상태로_생성된다() -> None:
    refund = make_requested_refund()

    assert refund.status == RefundStatus.REQUESTED
    assert refund.payment_id == "payment-1"
    assert refund.amount == 5000
    assert refund.reason == "단순 변심"
    assert refund.refund_id
    assert refund.decision_note is None
    assert refund.pull_events() == []


def test_approve_요청된_환불은_승인되고_RefundApproved_이벤트가_발행된다() -> None:
    refund = make_requested_refund()

    refund.approve(account_id="account-1", owner_id="owner-1")

    assert refund.status == RefundStatus.APPROVED
    assert refund.decision_note == "환불이 승인되었습니다."
    events = refund.pull_events()
    assert len(events) == 1
    event = events[0]
    assert event.refund_id == refund.refund_id
    assert event.payment_id == "payment-1"
    assert event.account_id == "account-1"
    assert event.owner_id == "owner-1"
    assert event.amount == 5000


def test_approve_이미_승인된_환불은_다시_승인할_수_없다() -> None:
    refund = make_requested_refund()
    refund.approve(account_id="account-1", owner_id="owner-1")

    with pytest.raises(RefundApproveRequiresRequestedRefundError):
        refund.approve(account_id="account-1", owner_id="owner-1")


def test_reject_요청된_환불은_거부되고_사유가_decision_note에_담긴다() -> None:
    refund = make_requested_refund()

    refund.reject("환불 금액이 결제 금액을 초과할 수 없습니다.")

    assert refund.status == RefundStatus.REJECTED
    assert refund.decision_note == "환불 금액이 결제 금액을 초과할 수 없습니다."
    assert refund.pull_events() == []


def test_reject_이미_거부된_환불은_다시_거부할_수_없다() -> None:
    refund = make_requested_refund()
    refund.reject("사유")

    with pytest.raises(RefundRejectRequiresRequestedRefundError):
        refund.reject("다른 사유")


def test_complete_승인된_환불은_완료로_전환된다() -> None:
    refund = make_requested_refund()
    refund.approve(account_id="account-1", owner_id="owner-1")

    refund.complete()

    assert refund.status == RefundStatus.COMPLETED


def test_complete_요청_상태의_환불은_완료로_전환할_수_없다() -> None:
    refund = make_requested_refund()

    with pytest.raises(RefundCompleteRequiresApprovedRefundError):
        refund.complete()
