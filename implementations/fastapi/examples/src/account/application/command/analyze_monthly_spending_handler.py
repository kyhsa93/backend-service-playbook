from __future__ import annotations

from datetime import datetime

from ...domain.repository import AccountRepository
from ...domain.spending_analysis import SpendingAnalysis
from ...domain.spending_analysis_repository import SpendingAnalysisRepository

PAGE_SIZE = 200


def _add_months(year: int, month: int, delta: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + delta
    new_year, new_month = divmod(total, 12)
    return new_year, new_month + 1


def spending_analysis_period_range(analysis_month: str) -> tuple[datetime, datetime, datetime, datetime]:
    """Derives the [month_start, month_end) and [previous_month_start, previous_month_end)
    UTC calendar boundaries purely from analysis_month ("YYYY-MM") — mirrors nestjs's
    computePreviousSpendingAnalysisPeriod's calendar math, just re-derived from the fixed
    period string instead of "now", the same trade-off the go port's
    spendingAnalysisPeriodRange already makes. Since analysis_month itself was fixed at
    enqueue time (by the Scheduler), re-deriving its boundaries here — even if this Handler
    runs on a delayed/backlogged Task, possibly days later — always analyzes the same month.
    """
    year, month = (int(part) for part in analysis_month.split("-"))
    month_start = datetime(year, month, 1)
    next_year, next_month = _add_months(year, month, 1)
    month_end = datetime(next_year, next_month, 1)
    prev_year, prev_month = _add_months(year, month, -1)
    previous_month_start = datetime(prev_year, prev_month, 1)
    previous_month_end = month_start
    return month_start, month_end, previous_month_start, previous_month_end


class AnalyzeMonthlySpendingHandler:
    """The Command Service for the monthly spending-analysis ETL batch. Since this is a
    system-driven use case triggered once a month by the Task Queue
    (account.analyze-monthly-spending), there is no Command dataclass representing a user
    request (the same reason as ApplyDailyInterestHandler/SendMonthlyCardStatementHandler)
    — analysis_month is instead the one piece of state the Scheduler fixed at enqueue time.

    The ETL, in full: Extract (paginate every ACTIVE account, summarize its and the prior
    month's WITHDRAWAL transactions), Transform (SpendingAnalysis.create's %-change/trend
    calculation), Load (one row per account per month into spending_analysis). The output is
    a queryable read-model row, not a file — the value is precomputing an aggregate a client
    would otherwise have to re-derive from potentially many raw Transaction rows on every
    request.
    """

    def __init__(self, repo: AccountRepository, analysis_repo: SpendingAnalysisRepository) -> None:
        self._repo = repo
        self._analysis_repo = analysis_repo

    async def execute(self, analysis_month: str) -> int:
        month_start, month_end, previous_month_start, previous_month_end = spending_analysis_period_range(
            analysis_month
        )
        analyzed_count = 0
        page = 0

        while True:
            accounts, total = await self._repo.find_accounts(page=page, take=PAGE_SIZE, status=["ACTIVE"])
            if not accounts:
                break

            for account in accounts:
                already_analyzed = await self._analysis_repo.has_analysis(account.account_id, analysis_month)
                if already_analyzed:
                    continue

                current_count, current_total = await self._repo.summarize_transactions(
                    account.account_id, ["WITHDRAWAL"], month_start, month_end
                )
                _previous_count, previous_total = await self._repo.summarize_transactions(
                    account.account_id, ["WITHDRAWAL"], previous_month_start, previous_month_end
                )

                analysis = SpendingAnalysis.create(
                    account_id=account.account_id,
                    analysis_month=analysis_month,
                    total_amount=current_total,
                    transaction_count=current_count,
                    previous_total_amount=previous_total,
                )
                await self._analysis_repo.save_analysis(analysis)
                analyzed_count += 1

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return analyzed_count
