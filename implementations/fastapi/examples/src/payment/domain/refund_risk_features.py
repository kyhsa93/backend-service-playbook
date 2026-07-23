from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class RefundRiskFeatures:
    """The feature vector RefundFraudRiskScorer scores. Assembled by the Application layer
    (request_refund_handler.py) from the requester's refund history
    (RefundRepository.summarize_refunds_by_owner) plus the current Payment/Refund pair — never
    by the Domain Service or the scorer itself, so both stay decoupled from how history is
    stored/queried.
    """

    refund_count_last_30_days: float
    rejected_refund_count_last_30_days: float
    refund_to_payment_amount_ratio: float
    minutes_since_payment: float
