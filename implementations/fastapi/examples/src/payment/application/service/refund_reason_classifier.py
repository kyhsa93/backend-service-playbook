from __future__ import annotations

from abc import ABC, abstractmethod

from ...domain.refund import RefundReasonCategory


class RefundReasonClassifier(ABC):
    """A Technical Service (see root docs/architecture/domain-service.md) wrapping a
    self-hosted LLM call — the same placement/shape as TransactionAutoCategorizer, just
    classifying a refund's free-text reason instead of a transaction's merchant name. Ops-
    analytics input only (see refund_reason_insights_query.py) — this Technical Service is
    never invoked from, or its result ever read by, RequestRefundHandler/
    RefundEligibilityService.
    """

    @abstractmethod
    async def classify(self, reason: str) -> RefundReasonCategory: ...
