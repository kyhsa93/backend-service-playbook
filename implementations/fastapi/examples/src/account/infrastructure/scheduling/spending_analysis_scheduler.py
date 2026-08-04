from __future__ import annotations

import logging
from datetime import datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ....common.clock import utc_now
from ....task_queue.task_outbox_writer import TaskOutboxWriter

logger = logging.getLogger(__name__)

TASK_TYPE = "account.analyze-monthly-spending"
GROUP_ID = "account.spending-analysis"


def _add_months(year: int, month: int, delta: int) -> tuple[int, int]:
    total = year * 12 + (month - 1) + delta
    new_year, new_month = divmod(total, 12)
    return new_year, new_month + 1


def compute_previous_spending_analysis_period(now: datetime) -> tuple[str, datetime, datetime]:
    """A pure function computing account.analyze-monthly-spending's target period — "the
    previous month" — by the UTC calendar. Mirrors nestjs's
    computePreviousSpendingAnalysisPeriod's calendar math exactly, though only the
    identifying period string plus its own [month_start, month_end) boundary are returned
    here (not also "the month before that") — that additional boundary is instead
    re-derived deterministically from the period string alone, inside
    analyze_monthly_spending_handler.spending_analysis_period_range. This mirrors the
    existing fastapi idiom one file over (card's previous_month_period), and the same
    trade-off the go port already makes.

    Returns (analysis_month "YYYY-MM", month_start, month_end).
    """
    this_month_start = datetime(now.year, now.month, 1)
    prev_year, prev_month = _add_months(now.year, now.month, -1)
    month_start = datetime(prev_year, prev_month, 1)
    month_end = this_month_start
    analysis_month = f"{prev_year:04d}-{prev_month:02d}"
    return analysis_month, month_start, month_end


async def enqueue_monthly_spending_analysis(session_factory: async_sessionmaker[AsyncSession]) -> None:
    """Loads the account.analyze-monthly-spending Task — extracted out of the Cron job body
    so a test can trigger it directly instead of waiting for a real Cron tick (the same
    reason nestjs's e2e test calls `scheduler.enqueueMonthlySpendingAnalysis()` directly and
    the go port exports `EnqueueMonthlySpendingAnalysis`).

    Unlike interest_scheduler/statement_scheduler (whose Task payload is always `{}`,
    because "today"/"this month" is cheaply recomputed from the clock inside the Command
    Service itself), analysis_month is computed here, at enqueue time, and carried through
    the Task payload as-is. If the Consumer instead recomputed "which month" from the actual
    clock at processing time, a delayed/backlogged run could close out the wrong month —
    the same reasoning nestjs/go/java-springboot's ports of this exact feature already
    apply, deviating from this file's own simpler siblings for that reason.
    """
    analysis_month, _month_start, _month_end = compute_previous_spending_analysis_period(utc_now())
    dedup_id = f"{TASK_TYPE}-{analysis_month}"
    try:
        async with session_factory() as session:
            await TaskOutboxWriter(session).enqueue(
                task_type=TASK_TYPE,
                payload={"analysis_month": analysis_month},
                group_id=GROUP_ID,
                deduplication_id=dedup_id,
            )
            await session.commit()
    except Exception:  # noqa: BLE001 - a Cron exception must be logged explicitly (scheduling.md)
        logger.exception("Failed to enqueue the monthly spending analysis Task")
        # Not re-raised — it will be retried on the next tick (the 1st of the next month).


def start_spending_analysis_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    """The Scheduler (Infrastructure layer) for the monthly spending-analysis ETL batch —
    the same principle as interest_scheduler.py/statement_scheduler.py (enqueue only, no
    business logic; the actual summarization/%-change computation is
    AccountTaskController.analyze_monthly_spending() -> AnalyzeMonthlySpendingHandler's job).

    Scheduled an hour after the card-statement job (02:00 UTC vs. 01:00 UTC) so the two
    monthly batch jobs don't contend for the database at the exact same moment — mirrors
    nestjs/java-springboot's own scheduling choice for this feature.
    """
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", day=1, hour=2, minute=0, id="analyze-monthly-spending")
    async def _run() -> None:
        await enqueue_monthly_spending_analysis(session_factory)

    scheduler.start()
    return scheduler
