from __future__ import annotations

import asyncio
import json
import logging

import aioboto3
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config.aws_config import AwsConfig
from ..config.sqs_config import SqsConfig
from .task_outbox_model import TaskOutboxModel

logger = logging.getLogger(__name__)

POLL_INTERVAL_SECONDS = 1
BATCH_SIZE = 100


class TaskOutboxPoller:
    """`task_outbox`(processed=False)를 읽어 FIFO Task 큐로 발행만 담당한다 — Domain Event의
    `OutboxPoller`(src/outbox/outbox_poller.py)와 완전히 동일한 책임 분리다: 어떤
    TaskController도 직접 호출하지 않는다(TaskConsumer의 몫). Scheduler는 이 클래스를
    참조하지 않는다 — 참조하면 outbox-no-sync-drain과 동일한 취지로 "적재와 실행이 한
    호출 안에 다시 묶이는" 회귀가 된다.

    FIFO 큐이므로 MessageGroupId(병렬성 경계)/MessageDeduplicationId(날짜 기반 dedup)를
    함께 보낸다 — scheduling.md "MessageGroupId 전략"·"Cron 다중 인스턴스 안전성" 참고.
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        while True:
            try:
                await self._drain_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - 폴링 루프는 예외로 죽으면 안 된다
                logger.exception("Task Outbox 폴링 실패")
            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    async def _drain_once(self) -> None:
        queue_url = SqsConfig().task_queue_url  # type: ignore[call-arg]

        async with self._session_factory() as session:
            stmt = (
                select(TaskOutboxModel)
                .where(TaskOutboxModel.processed.is_(False))
                .order_by(TaskOutboxModel.created_at)
                .limit(BATCH_SIZE)
            )
            rows = (await session.execute(stmt)).scalars().all()
            if not rows:
                return

            async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:  # type: ignore[call-arg]
                for row in rows:
                    try:
                        body = json.loads(row.payload)
                        body["task_outbox_task_id"] = row.task_id
                        await sqs_client.send_message(
                            QueueUrl=queue_url,
                            MessageBody=json.dumps(body),
                            MessageAttributes={"taskType": {"DataType": "String", "StringValue": row.task_type}},
                            MessageGroupId=row.group_id,
                            MessageDeduplicationId=row.deduplication_id,
                        )
                        row.processed = True
                    except Exception:  # noqa: BLE001 - 발행 실패 행은 다음 tick에서 재시도
                        logger.exception("Task SQS 발행 실패: task_type=%s task_id=%s", row.task_type, row.task_id)

            await session.commit()
