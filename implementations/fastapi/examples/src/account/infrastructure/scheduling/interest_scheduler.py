from __future__ import annotations

import logging
from datetime import date

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ....task_queue.task_outbox_writer import TaskOutboxWriter

logger = logging.getLogger(__name__)

TASK_TYPE = "account.interest.apply"
GROUP_ID = "account.interest"


def start_interest_scheduler(session_factory: async_sessionmaker[AsyncSession]) -> AsyncIOScheduler:
    """The Scheduler (Infrastructure layer) for the regular interest-payment batch.
    Following scheduling.md's "Separating the Scheduler's role" principle, this Cron
    handler does nothing but enqueue a Task — the actual interest calculation/payment is
    the job of AccountTaskController.apply_daily_interest() (→ ApplyDailyInterestHandler),
    which TaskConsumer receives and calls.

    A Cron tick has no natural DB transaction, so the single row insert of
    `TaskOutboxWriter.enqueue()` itself provides atomicity (see "The Task Outbox pattern" in
    scheduling.md). A date-based deduplication_id ensures only 1 row gets loaded even if
    multiple instances tick on the same day at the same time.
    """
    scheduler = AsyncIOScheduler()

    @scheduler.scheduled_job("cron", hour=0, minute=0, id="apply-daily-interest")
    async def _run() -> None:
        try:
            today = date.today()
            async with session_factory() as session:
                await TaskOutboxWriter(session).enqueue(
                    task_type=TASK_TYPE,
                    payload={},
                    group_id=GROUP_ID,
                    deduplication_id=f"{TASK_TYPE}-{today.isoformat()}",
                )
                await session.commit()
        except Exception:  # noqa: BLE001 - a Cron exception must be logged explicitly (scheduling.md)
            logger.exception("Failed to enqueue the regular interest-payment Task")
            # Not re-raised — it will be retried on the next tick (the next day).

    scheduler.start()
    return scheduler
