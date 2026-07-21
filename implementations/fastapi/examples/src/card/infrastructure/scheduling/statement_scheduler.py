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
    """매월 카드 사용내역 발송 배치의 Scheduler(Infrastructure 레이어) —
    account/infrastructure/scheduling/interest_scheduler.py와 동일한 원칙(enqueue만,
    비즈니스 로직 없음). 매월 1일 새벽에 Task를 enqueue하면, TaskConsumer가 수신해
    CardTaskController.send_monthly_statement()(→ SendMonthlyCardStatementHandler)를
    호출해 지난 한 달을 집계한다.

    deduplication_id는 "월" 단위(YYYY-MM)로 만든다 — 같은 달 여러 인스턴스가 동시에
    tick해도 1건만 적재된다(scheduling.md "Cron 다중 인스턴스 안전성").
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
        except Exception:  # noqa: BLE001 - Cron 예외는 명시적으로 로깅해야 한다(scheduling.md)
            logger.exception("매월 카드 사용내역 발송 Task enqueue 실패")
            # 예외를 재throw하지 않는다 — 다음 tick(다음달)에서 재시도된다.

    scheduler.start()
    return scheduler
