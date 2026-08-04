from __future__ import annotations

import logging
from datetime import datetime

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ....common.clock import utc_now
from ....task_queue.task_outbox_writer import TaskOutboxWriter

logger = logging.getLogger(__name__)

TASK_TYPE = "account.forecast-spending"
GROUP_ID = "account.spending-forecast"


def compute_spending_forecast_month(now: datetime) -> str:
    """A pure function computing account.forecast-spending's target period — "the current
    month" (the one that just started, when this runs on the 1st at 03:00 UTC, an hour after
    the spending-analysis job at 02:00 has finished writing last month's row). The Scheduler
    carries this function's result through the Task payload as-is, the same reason as
    compute_previous_spending_analysis_period: recomputing "which month" from the clock at
    processing time (rather than at enqueue time) could target the wrong month if processing
    is delayed by a queue backlog.
    """
    return f"{now.year:04d}-{now.month:02d}"


async def enqueue_monthly_spending_forecast(session_factory: async_sessionmaker[AsyncSession]) -> None:
    """Loads the account.forecast-spending Task — extracted out of the Cron job body so a test
    can trigger it directly instead of waiting for a real Cron tick (the same reason
    enqueue_monthly_spending_analysis is factored out this way)."""
    forecast_month = compute_spending_forecast_month(utc_now())
    dedup_id = f"{TASK_TYPE}-{forecast_month}"
    try:
        async with session_factory() as session:
            await TaskOutboxWriter(session).enqueue(
                task_type=TASK_TYPE,
                payload={"forecast_month": forecast_month},
                group_id=GROUP_ID,
                deduplication_id=dedup_id,
            )
            await session.commit()
    except Exception:  # noqa: BLE001 - a Cron exception must be logged explicitly (scheduling.md)
        logger.exception("Failed to enqueue the monthly spending forecast Task")
        # Not re-raised — it will be retried on the next tick (the 1st of the next month).


def start_spending_forecast_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    """The Scheduler (Infrastructure layer) for the monthly spending-forecast batch — the same
    principle as spending_analysis_scheduler.py (enqueue only, no business logic; the actual
    training/prediction is AccountTaskController.forecast_spending() ->
    ForecastSpendingHandler's job).

    Scheduled an hour after the spending-analysis job (03:00 UTC vs. 02:00 UTC) so this
    month's history (last month's freshly-written analysis row) is guaranteed to exist before
    training reads it.
    """
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", day=1, hour=3, minute=0, id="forecast-spending")
    async def _run() -> None:
        await enqueue_monthly_spending_forecast(session_factory)

    scheduler.start()
    return scheduler
