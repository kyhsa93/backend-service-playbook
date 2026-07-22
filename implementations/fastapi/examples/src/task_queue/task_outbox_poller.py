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
    """Reads `task_outbox` (processed=False) and is responsible only for publishing to the
    FIFO Task queue — exactly the same separation of responsibilities as the Domain
    Event's `OutboxPoller` (src/outbox/outbox_poller.py): it never calls any
    TaskController directly (that's TaskConsumer's job). The Scheduler never references
    this class — if it did, it would be a regression, in the same spirit as
    outbox-no-sync-drain, of "loading and executing being bundled back into a single
    call."

    Since it's a FIFO queue, MessageGroupId (the parallelism boundary)/MessageDeduplicationId
    (date-based dedup) are sent along with it — see "The MessageGroupId strategy"·"Cron
    multi-instance safety" in scheduling.md.
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
            except Exception:  # noqa: BLE001 - the polling loop must never die from an exception
                logger.exception("Task Outbox polling failed")
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
                    except Exception:  # noqa: BLE001 - a row that fails to publish is retried on the next tick
                        logger.exception(
                            "Failed to publish Task to SQS: task_type=%s task_id=%s", row.task_type, row.task_id
                        )

            await session.commit()
