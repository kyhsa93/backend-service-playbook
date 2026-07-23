from __future__ import annotations

from dataclasses import dataclass

from .payment import Payment
from .payment_status import PaymentStatus
from .refund import Refund
from .refund_reason_classification import RefundReasonCategory, RefundReasonClassification


@dataclass(frozen=True)
class RefundDecision:
    approved: bool
    reason: str | None = None


# The fraud-risk score is produced upstream by RefundReasonClassifier (a Technical Service
# wrapping an LLM call) — this Domain Service never calls it and doesn't know an LLM produced
# it. It only receives the already-computed classification as one more plain input alongside
# Payment/Refund, and applies its own fixed threshold. The LLM supplies a signal; this method
# still owns the actual approve/reject judgment.
FRAUD_RISK_REJECTION_THRESHOLD = 0.7

# A second, independent signal — produced upstream by RefundFraudRiskScorer (a Technical
# Service trained on refund/payment history, see infrastructure/refund_fraud_risk_scorer_native_impl.py
# / refund_fraud_risk_scorer_http_impl.py). Kept as its own plain number with its own threshold
# rather than merged into RefundReasonClassification, since it's computed from an entirely
# different input (structured history, not the free-text reason) and can fire independently of
# the LLM's category/score.
ML_FRAUD_RISK_REJECTION_THRESHOLD = 0.8


class RefundEligibilityService:
    """A Domain Service — a pure class with no framework dependency. It is never registered
    in any DI container such as FastAPI's Depends — the Application layer instantiates it
    directly whenever needed (it's stateless, pure decision logic, so reuse across requests
    is not an issue).

    The decision "the original payment must be in the COMPLETED state, and the refund amount
    cannot exceed the payment amount" cannot be made by Payment alone or by Refund alone.
    Payment doesn't know about a refund attempt against it (a refund only exists as a Refund
    Aggregate), and Refund doesn't know the original payment's amount/status (it only
    references it via payment_id). Making this decision requires loading both Aggregates and
    comparing them in the same place, so this coordination logic can't be put into either
    Aggregate's method (doing so would require taking the other Aggregate whole as a
    parameter, breaking the boundary) — it lives here, in a separate Domain Service instead.
    (See root docs/architecture/domain-service.md, implementations/fastapi/docs/architecture/
    layer-architecture.md)

    The `classification` parameter is a plain value already computed upstream by
    RefundReasonClassifier (a Technical Service wrapping an LLM call — see the Technical
    Service section of docs/architecture/domain-service.md). This method never calls it and
    doesn't know an LLM produced the value; it only weighs the fraud-risk signal alongside
    its other checks and still owns the actual judgment.

    `ml_fraud_risk_score` is likewise a plain value already computed upstream by
    RefundFraudRiskScorer (a second, independent Technical Service — trained on the
    requester's refund/payment *history pattern* rather than the free-text reason
    RefundReasonClassifier handles). This method never calls it either; it only applies its
    own fixed threshold to the number it receives.
    """

    def evaluate(
        self,
        payment: Payment,
        refund: Refund,
        classification: RefundReasonClassification,
        ml_fraud_risk_score: float,
    ) -> RefundDecision:
        if payment.status != PaymentStatus.COMPLETED:
            return RefundDecision(approved=False, reason="A refund can only be requested for a completed payment.")
        if refund.amount > payment.amount:
            return RefundDecision(approved=False, reason="The refund amount cannot exceed the payment amount.")
        if (
            classification.category == RefundReasonCategory.FRAUD_SUSPECTED
            and classification.fraud_risk_score >= FRAUD_RISK_REJECTION_THRESHOLD
        ):
            return RefundDecision(
                approved=False,
                reason="This refund reason was flagged as high fraud risk and requires manual review.",
            )
        if ml_fraud_risk_score >= ML_FRAUD_RISK_REJECTION_THRESHOLD:
            return RefundDecision(
                approved=False,
                reason=(
                    "This refund pattern was flagged as high risk by the fraud-risk model and requires manual review."
                ),
            )
        return RefundDecision(approved=True)
