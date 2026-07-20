from __future__ import annotations

import asyncio
import json
import logging

import aioboto3
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config.aws_config import AwsConfig
from ..config.sqs_config import SqsConfig
from .event_handlers import build_event_handlers

logger = logging.getLogger(__name__)


class OutboxConsumer:
    """SQS를 long polling(`receive_message`의 `WaitTimeSeconds`)으로 수신 대기하다가
    메시지를 받으면 `eventType`(MessageAttributes)으로 `build_event_handlers()`가 조립한
    dict에서 핸들러를 찾아 호출한다 — Domain Event Handler(application/event/)든
    Integration Event Controller(interface/integration_event/)든 이 하나의 Consumer를
    거친다.

    핸들러 성공 → `delete_message`로 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) →
    삭제하지 않는다 — SQS의 visibility timeout이 지나면 자동 재전달된다(at-least-once).
    이 저장소가 요구하는 EventHandler 멱등성(domain-events.md)이 바로 이 재전달을 전제한다.
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        """`main.py`의 lifespan이 `asyncio.create_task()`로 기동하는 백그라운드 루프.
        Task가 취소되면 진행 중인 `receive_message` 대기(최대 `WaitTimeSeconds`)를 즉시
        끝내고 종료한다(Graceful Shutdown) — `sqs_client`는 `async with` 블록을 벗어나며
        정리된다.
        """
        queue_url = SqsConfig().domain_event_queue_url  # type: ignore[call-arg]
        async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:  # type: ignore[call-arg]
            while True:
                try:
                    result = await sqs_client.receive_message(
                        QueueUrl=queue_url,
                        MaxNumberOfMessages=10,
                        MessageAttributeNames=["eventType"],
                        WaitTimeSeconds=5,
                    )
                except asyncio.CancelledError:
                    raise
                except Exception:  # noqa: BLE001 - 수신 루프는 예외로 죽으면 안 된다
                    logger.exception("SQS 수신 실패")
                    await asyncio.sleep(1)
                    continue

                for message in result.get("Messages", []):
                    await self._handle_message(sqs_client, queue_url, message)

    async def _handle_message(self, sqs_client, queue_url: str, message: dict) -> None:
        event_type = message.get("MessageAttributes", {}).get("eventType", {}).get("StringValue")
        try:
            if not event_type:
                raise ValueError("eventType 메시지 속성이 없습니다.")

            payload = json.loads(message.get("Body", "{}"))
            async with self._session_factory() as session:
                handler = build_event_handlers(session).get(event_type)
                if handler is None:
                    raise ValueError(f"등록된 핸들러 없음: {event_type}")
                await handler(payload)
                await session.commit()

            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - 삭제하지 않아야 visibility timeout 이후 재시도된다
            logger.exception("이벤트 처리 실패: event_type=%s", event_type)
