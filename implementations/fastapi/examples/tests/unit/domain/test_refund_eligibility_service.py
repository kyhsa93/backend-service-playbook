from src.payment.domain.payment import Payment
from src.payment.domain.refund import Refund
from src.payment.domain.refund_eligibility_service import RefundEligibilityService


def make_completed_payment(amount: int = 10000) -> Payment:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=amount)
    payment.complete()
    return payment


def test_evaluate_완료된_결제에_결제금액_이하_환불은_승인된다() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=10000, reason="단순 변심")

    decision = RefundEligibilityService().evaluate(payment, refund)

    assert decision.approved is True
    assert decision.reason is None


def test_evaluate_완료되지_않은_결제에_대한_환불은_거부된다() -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=10000)  # PENDING
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="단순 변심")

    decision = RefundEligibilityService().evaluate(payment, refund)

    assert decision.approved is False
    assert decision.reason == "완료된 결제에 대해서만 환불을 요청할 수 있습니다."


def test_evaluate_환불_금액이_결제_금액을_초과하면_거부된다() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=10001, reason="단순 변심")

    decision = RefundEligibilityService().evaluate(payment, refund)

    assert decision.approved is False
    assert decision.reason == "환불 금액은 결제 금액을 초과할 수 없습니다."


def test_evaluate_취소된_결제에_대한_환불은_거부된다() -> None:
    payment = make_completed_payment(amount=10000)
    payment.cancel("고객 요청")
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="단순 변심")

    decision = RefundEligibilityService().evaluate(payment, refund)

    assert decision.approved is False
    assert decision.reason == "완료된 결제에 대해서만 환불을 요청할 수 있습니다."
