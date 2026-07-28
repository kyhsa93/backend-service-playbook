from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date


@dataclass(frozen=True)
class RefundReasonCategoryCount:
    category: str
    count: int


class RefundReasonInsightsQuery(ABC):
    """A read-only, ops-analytics aggregate query over every classified refund's
    reason_category — not scoped by owner_id/requester_id, since its whole purpose is to
    surface refund-reason patterns across every refund, not one user's (see
    get_refund_reason_insights_handler.py, payment_router.py). This repo has no separate
    admin-authorization boundary, so it is exposed behind the same baseline auth as every
    other endpoint; a production system would put this behind a dedicated ops/admin role
    instead.

    Deliberately not named count_by_category — a bare count-only method is forbidden
    (repository-pattern.md, harness rule 13/repository-naming); get_insights returns the full
    per-category breakdown in one call instead, the same shape RefundReasonInsightsResult
    exposes.
    """

    @abstractmethod
    async def get_insights(
        self, from_date: date | None = None, to_date: date | None = None
    ) -> list[RefundReasonCategoryCount]:
        """Returns one row per reason_category that has at least one classified refund in the
        given [from_date, to_date] range (inclusive) — omits categories with 0 refunds. The
        caller (GetRefundReasonInsightsHandler) sums count for total_classified.
        """
        ...
