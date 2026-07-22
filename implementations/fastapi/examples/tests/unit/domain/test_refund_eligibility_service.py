from src.payment.domain.payment import Payment
from src.payment.domain.refund import Refund
from src.payment.domain.refund_eligibility_service import RefundEligibilityService
from src.payment.domain.refund_reason_classification import RefundReasonCategory, RefundReasonClassification

NOT_FRAUD = RefundReasonClassification(category=RefundReasonCategory.DEFECTIVE_PRODUCT, fraud_risk_score=0.1)


def make_completed_payment(amount: int = 10000) -> Payment:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=amount)
    payment.complete()
    return payment


def test_evaluate_a_refund_at_or_under_the_payment_amount_for_a_completed_payment_is_approved() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=10000, reason="personal change of mind")

    decision = RefundEligibilityService().evaluate(payment, refund, NOT_FRAUD)

    assert decision.approved is True
    assert decision.reason is None


def test_evaluate_a_refund_for_a_non_completed_payment_is_rejected() -> None:
    payment = Payment.create(card_id="card-1", account_id="account-1", owner_id="owner-1", amount=10000)  # PENDING
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="personal change of mind")

    decision = RefundEligibilityService().evaluate(payment, refund, NOT_FRAUD)

    assert decision.approved is False
    assert decision.reason == "A refund can only be requested for a completed payment."


def test_evaluate_a_refund_exceeding_the_payment_amount_is_rejected() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=10001, reason="personal change of mind")

    decision = RefundEligibilityService().evaluate(payment, refund, NOT_FRAUD)

    assert decision.approved is False
    assert decision.reason == "The refund amount cannot exceed the payment amount."


def test_evaluate_a_refund_for_a_cancelled_payment_is_rejected() -> None:
    payment = make_completed_payment(amount=10000)
    payment.cancel("customer request")
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="personal change of mind")

    decision = RefundEligibilityService().evaluate(payment, refund, NOT_FRAUD)

    assert decision.approved is False
    assert decision.reason == "A refund can only be requested for a completed payment."


def test_evaluate_a_refund_classified_as_fraud_suspected_above_the_threshold_is_rejected() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="suspicious reason")
    classification = RefundReasonClassification(category=RefundReasonCategory.FRAUD_SUSPECTED, fraud_risk_score=0.9)

    decision = RefundEligibilityService().evaluate(payment, refund, classification)

    assert decision.approved is False
    assert decision.reason == "This refund reason was flagged as high fraud risk and requires manual review."


def test_evaluate_a_refund_classified_as_fraud_suspected_but_below_the_threshold_is_still_approved() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="a slightly odd reason")
    classification = RefundReasonClassification(category=RefundReasonCategory.FRAUD_SUSPECTED, fraud_risk_score=0.5)

    decision = RefundEligibilityService().evaluate(payment, refund, classification)

    assert decision.approved is True


def test_evaluate_a_refund_with_a_high_score_but_not_classified_as_fraud_suspected_is_still_approved() -> None:
    payment = make_completed_payment(amount=10000)
    refund = Refund.create(payment_id=payment.payment_id, amount=5000, reason="ordinary reason")
    classification = RefundReasonClassification(category=RefundReasonCategory.OTHER, fraud_risk_score=0.95)

    decision = RefundEligibilityService().evaluate(payment, refund, classification)

    assert decision.approved is True
