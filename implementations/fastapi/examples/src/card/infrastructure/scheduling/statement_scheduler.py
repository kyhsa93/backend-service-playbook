from __future__ import annotations

import logging
from datetime import date

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ....task_queue.task_outbox_writer import TaskOutboxWriter

logger = logging.getLogger(__name__)

TASK_TYPE = "card.statement.send"
GROUP_ID = "card.statement"


def start_statement_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    """The Scheduler (Infrastructure layer) for the monthly card-statement delivery batch —
    the same principle as
    account/infrastructure/scheduling/interest_scheduler.py (enqueue only, no business
    logic). Once a Task is enqueued in the early morning of the 1st of each month,
    TaskConsumer receives it and calls
    CardTaskController.send_monthly_statement() (→ SendMonthlyCardStatementHandler) to
    aggregate the past month.

    The deduplication_id is built per "month" (YYYY-MM) — only 1 row gets loaded even if
    multiple instances tick on the same month at the same time (see "Cron multi-instance
    safety" in scheduling.md).
    """
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", day=1, hour=1, minute=0, id="send-monthly-card-statement")
    async def _run() -> None:
        try:
            today = date.today()
            month_key = f"{today.year:04d}-{today.month:02d}"
            async with session_factory() as session:
                await TaskOutboxWriter(session).enqueue(
                    task_type=TASK_TYPE,
                    payload={},
                    group_id=GROUP_ID,
                    deduplication_id=f"{TASK_TYPE}-{month_key}",
                )
                await session.commit()
        except Exception:  # noqa: BLE001 - a Cron exception must be logged explicitly (scheduling.md)
            logger.exception("Failed to enqueue the monthly card-statement delivery Task")
            # Not re-raised — it will be retried on the next tick (next month).

    scheduler.start()
    return scheduler
