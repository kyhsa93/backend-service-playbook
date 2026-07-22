from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class RefundReasonCategory(str, Enum):
    """The shape of a refund reason's classification result — a plain Domain-layer value, not
    tied to how it was produced. RefundEligibilityService (refund_eligibility_service.py)
    reads this as one more input alongside Payment/Refund; it never imports the Application-
    layer RefundReasonClassifier interface that produces it (that would violate domain-layer
    isolation — the Domain layer must not depend on any other layer). The Technical Service
    interface (application/service/refund_reason_classifier.py) imports this type from here
    instead. (See root docs/architecture/domain-service.md.)
    """

    DEFECTIVE_PRODUCT = "defective_product"
    NOT_AS_DESCRIBED = "not_as_described"
    DUPLICATE_CHARGE = "duplicate_charge"
    CHANGED_MIND = "changed_mind"
    FRAUD_SUSPECTED = "fraud_suspected"
    OTHER = "other"


@dataclass(frozen=True)
class RefundReasonClassification:
    category: RefundReasonCategory
    fraud_risk_score: float
