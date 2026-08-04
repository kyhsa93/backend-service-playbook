from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from ...common.clock import utc_now
from ...common.generate_id import generate_id

SpendingTrend = Literal["INCREASING", "DECREASING", "STABLE"]

TREND_THRESHOLD_PERCENT = 10


def _round_half_up(value: float) -> int:
    """Python's built-in round() rounds half-to-even (banker's rounding), which diverges
    from JS's Math.round/Java's Math.round (round half up, i.e. floor(x + 0.5)) exactly at
    a .5 tie — this repository's nestjs reference (SpendingAnalysis.create) and its
    java-springboot port both rely on that half-up behavior, so this helper reproduces it
    exactly rather than trusting round()."""
    return math.floor(value + 0.5)


# A materialized read-model row — the ETL's precomputed answer to "how did this account's
# spending change this month," produced monthly by AnalyzeMonthlySpendingHandler and served
# as-is by GetSpendingAnalysisHandler. No business invariant lives here beyond the one real
# "transform" step (turning two raw totals into a %-change and a trend label), so this stays
# a plain data holder (a frozen dataclass, the same modeling choice as Transaction) rather
# than a stateful Aggregate — no Repository method ever loads it back into memory to call a
# domain method on it (unlike Account).
@dataclass(frozen=True)
class SpendingAnalysis:
    analysis_id: str
    account_id: str
    analysis_month: str
    total_amount: int
    transaction_count: int
    average_amount: int
    change_from_previous_month: int
    trend: SpendingTrend
    created_at: datetime

    @classmethod
    def create(
        cls,
        account_id: str,
        analysis_month: str,
        total_amount: int,
        transaction_count: int,
        previous_total_amount: int,
    ) -> SpendingAnalysis:
        """previous_total_amount is always a real computed sum (0 when the account had no
        withdrawals last month, never None) — there's no "unknown baseline" case to
        special-case, since a brand-new account with no prior-month history genuinely did
        spend 0 that month.
        """
        average_amount = _round_half_up(total_amount / transaction_count) if transaction_count > 0 else 0

        if previous_total_amount == 0:
            change_from_previous_month = 0 if total_amount == 0 else 100
        else:
            change_from_previous_month = _round_half_up(
                (total_amount - previous_total_amount) / previous_total_amount * 100
            )

        trend: SpendingTrend = "STABLE"
        if change_from_previous_month > TREND_THRESHOLD_PERCENT:
            trend = "INCREASING"
        elif change_from_previous_month < -TREND_THRESHOLD_PERCENT:
            trend = "DECREASING"

        return cls(
            analysis_id=generate_id(),
            account_id=account_id,
            analysis_month=analysis_month,
            total_amount=total_amount,
            transaction_count=transaction_count,
            average_amount=average_amount,
            change_from_previous_month=change_from_previous_month,
            trend=trend,
            created_at=utc_now(),
        )
