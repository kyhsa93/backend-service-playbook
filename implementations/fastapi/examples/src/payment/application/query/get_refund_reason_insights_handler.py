from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ...domain.refund_reason_insights_query import RefundReasonInsightsQuery
from .result import RefundReasonCategoryCountResult, RefundReasonInsightsResult


@dataclass
class GetRefundReasonInsightsQuery:
    from_date: date | None = None
    to_date: date | None = None


class GetRefundReasonInsightsHandler:
    """An ops/analytics read model, not a per-owner one — deliberately not scoped by any
    requester_id, since its whole purpose is to surface refund-reason patterns across every
    refund, not one user's. This repo has no separate admin-authorization boundary, so the
    endpoint is exposed behind the same baseline auth as every other endpoint (see
    payment_router.py); a production system would put this behind a dedicated ops/admin role
    instead.
    """

    def __init__(self, insights_query: RefundReasonInsightsQuery) -> None:
        self._insights_query = insights_query

    async def execute(self, query: GetRefundReasonInsightsQuery) -> RefundReasonInsightsResult:
        counts = await self._insights_query.get_insights(query.from_date, query.to_date)
        return RefundReasonInsightsResult(
            counts=[RefundReasonCategoryCountResult(category=c.category, count=c.count) for c in counts],
            total_classified=sum(c.count for c in counts),
        )
