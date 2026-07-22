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
    """

    def evaluate(self, payment: Payment, refund: Refund, classification: RefundReasonClassification) -> RefundDecision:
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
        return RefundDecision(approved=True)
