from __future__ import annotations

import asyncio
import json
import logging

import aioboto3
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config.aws_config import AwsConfig
from ..config.sqs_config import SqsConfig
from .task_handlers import build_task_handlers

logger = logging.getLogger(__name__)


class TaskConsumer:
    """Waits to receive from the Task queue via long polling, and once a message is
    received, looks up the TaskController method in the dict `build_task_handlers()`
    assembled by `taskType` (MessageAttributes) and calls it — exactly the same structure
    as the Domain Event's `OutboxConsumer` (src/outbox/outbox_consumer.py).

    Since a TaskController lets exceptions propagate as-is (scheduling.md), what needs to
    happen here is the same as OutboxConsumer: success → `delete_message` (ack), failure
    (or no registered handler) → not deleted. Once the FIFO queue's visibility timeout
    passes, it's automatically redelivered, and once `maxReceiveCount` is exceeded, it's
    isolated into the DLQ (see "DLQ monitoring" in scheduling.md).
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        queue_url = SqsConfig().task_queue_url  # type: ignore[call-arg]
        async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:  # type: ignore[call-arg]
            while True:
                try:
                    result = await sqs_client.receive_message(
                        QueueUrl=queue_url,
                        MaxNumberOfMessages=10,
                        MessageAttributeNames=["taskType"],
                        WaitTimeSeconds=5,
                    )
                except asyncio.CancelledError:
                    raise
                except Exception:  # noqa: BLE001 - the receive loop must never die from an exception
                    logger.exception("Failed to receive Task from SQS")
                    await asyncio.sleep(1)
                    continue

                for message in result.get("Messages", []):
                    await self._handle_message(sqs_client, queue_url, message)

    async def _handle_message(self, sqs_client, queue_url: str, message: dict) -> None:
        task_type = message.get("MessageAttributes", {}).get("taskType", {}).get("StringValue")
        try:
            if not task_type:
                raise ValueError("The taskType message attribute is missing.")

            payload = json.loads(message.get("Body", "{}"))
            async with self._session_factory() as session:
                handler = build_task_handlers(session).get(task_type)
                if handler is None:
                    raise ValueError(f"No registered Task handler: {task_type}")
                await handler(payload)
                await session.commit()

            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - it must not be deleted, so it's retried after the visibility timeout
            logger.exception("Failed to process Task: task_type=%s", task_type)
