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
    """정기 이자 지급 배치의 Scheduler(Infrastructure 레이어). scheduling.md "Scheduler 역할
    분리" 원칙대로, 이 Cron 핸들러는 Task를 enqueue하는 것 말고는 아무것도 하지 않는다 —
    실제 이자 계산·지급은 TaskConsumer가 수신해 호출하는
    AccountTaskController.apply_daily_interest()(→ ApplyDailyInterestHandler)의 몫이다.

    Cron tick에는 자연스러운 DB 트랜잭션이 없으므로, `TaskOutboxWriter.enqueue()` 단일
    row insert 자체가 원자성을 만든다(scheduling.md "Task Outbox 패턴"). 날짜 기반
    deduplication_id로 여러 인스턴스가 같은 날 동시에 tick해도 1건만 적재되게 한다.
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
        except Exception:  # noqa: BLE001 - Cron 예외는 명시적으로 로깅해야 한다(scheduling.md)
            logger.exception("정기 이자 지급 Task enqueue 실패")
            # 예외를 재throw하지 않는다 — 다음 tick(다음날)에서 재시도된다.

    scheduler.start()
    return scheduler
