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
    """Task 큐를 long polling으로 수신 대기하다가 메시지를 받으면 `taskType`
    (MessageAttributes)으로 `build_task_handlers()`가 조립한 dict에서 TaskController
    메서드를 찾아 호출한다 — Domain Event의 `OutboxConsumer`(src/outbox/outbox_consumer.py)와
    완전히 동일한 구조다.

    TaskController가 예외를 그대로 전파하므로(scheduling.md), 여기서 해야 할 일도
    OutboxConsumer와 동일하다: 성공 → `delete_message`(ack), 실패(또는 등록된 핸들러 없음)
    → 삭제하지 않는다. FIFO 큐의 visibility timeout이 지나면 자동 재전달되고,
    `maxReceiveCount`를 넘기면 DLQ로 격리된다(scheduling.md "DLQ 모니터링").
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
                except Exception:  # noqa: BLE001 - 수신 루프는 예외로 죽으면 안 된다
                    logger.exception("Task SQS 수신 실패")
                    await asyncio.sleep(1)
                    continue

                for message in result.get("Messages", []):
                    await self._handle_message(sqs_client, queue_url, message)

    async def _handle_message(self, sqs_client, queue_url: str, message: dict) -> None:
        task_type = message.get("MessageAttributes", {}).get("taskType", {}).get("StringValue")
        try:
            if not task_type:
                raise ValueError("taskType 메시지 속성이 없습니다.")

            payload = json.loads(message.get("Body", "{}"))
            async with self._session_factory() as session:
                handler = build_task_handlers(session).get(task_type)
                if handler is None:
                    raise ValueError(f"등록된 Task 핸들러 없음: {task_type}")
                await handler(payload)
                await session.commit()

            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - 삭제하지 않아야 visibility timeout 이후 재시도된다
            logger.exception("Task 처리 실패: task_type=%s", task_type)
