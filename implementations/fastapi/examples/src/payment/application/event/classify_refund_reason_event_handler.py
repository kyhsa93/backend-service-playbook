from __future__ import annotations

import logging

from ...domain.refund_repository import RefundRepository
from ..service.refund_reason_classifier import RefundReasonClassifier

logger = logging.getLogger(__name__)


class ClassifyRefundReasonEventHandler:
    """Reacts to RefundRequested (published unconditionally by Refund.create(), before
    RefundEligibilityService's approve/reject judgment even runs) to classify the refund's
    free-text reason asynchronously, off the request hot path (RequestRefundHandler never
    calls an LLM directly) — purely for ops-analytics reporting, see
    refund_reason_insights_query.py. Its result is never read back into any eligibility/
    approval decision. Inherently idempotent (see domain-events.md): a retried delivery just
    re-runs the same find -> classify -> save cycle, landing on the same (or an equally
    acceptable) category.
    """

    def __init__(self, classifier: RefundReasonClassifier, refund_repo: RefundRepository) -> None:
        self._classifier = classifier
        self._refund_repo = refund_repo

    async def handle(self, payload: dict) -> None:
        refunds, _ = await self._refund_repo.find_refunds(page=0, take=1, refund_id=payload["refund_id"])
        refund = refunds[0] if refunds else None
        if refund is None:
            return

        category = await self._classifier.classify(payload["reason"])
        refund.categorize_reason(category)
        await self._refund_repo.save_refund(refund)
        logger.info("Refund reason classified: refund_id=%s category=%s", payload["refund_id"], category)
