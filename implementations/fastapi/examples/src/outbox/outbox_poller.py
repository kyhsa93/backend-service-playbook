from __future__ import annotations

import asyncio
import json
import logging

import aioboto3
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config.aws_config import AwsConfig
from ..config.sqs_config import SqsConfig
from .outbox_model import OutboxModel

logger = logging.getLogger(__name__)

POLL_INTERVAL_SECONDS = 1
BATCH_SIZE = 100


class OutboxPoller:
    """Outbox 테이블(`processed=False`)을 읽어 SQS로 발행만 담당한다("DB에 쌓인 이벤트를
    큐로 실어 나른다"). 어떤 EventHandler도 직접 호출하지 않는다 — 그건 OutboxConsumer의 몫이다.

    Command Handler는 이 클래스를 전혀 참조하지 않는다 — 참조하면 harness의
    outbox-no-sync-drain 규칙이 잡아낸다(domain-events.md). 이 클래스가 앱 기동 시
    `asyncio.create_task()`로 독립적으로 주기 실행되는 것 자체가 "저장 직후 같은 프로세스
    안에서 동기 드레인"을 제거하는 핵심이다 — Command Handler가 저장을 커밋하고 응답을
    반환한 뒤에도, 이 이벤트가 언제 큐로 나가는지는 다음 tick(최대 1초 뒤)까지 알 수 없다.

    `processed=True`는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는
    뜻이다 — 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility
    timeout + DLQ가 담당한다(docs/architecture/domain-events.md 참고).
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        # notification_service.py의 SesNotificationService와 동일한 관례 — boto3 Session은
        # 한 번만 만들고, 실제 client는 호출마다 (짧은 async with 블록으로) 연다.
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        """`main.py`의 lifespan이 `asyncio.create_task()`로 기동하는 백그라운드 루프.
        Task가 취소되면(`asyncio.CancelledError`) 그대로 전파해 루프를 끝낸다 — 진행
        중이던 한 tick의 드레인이 있다면 마무리하지 않고 즉시 중단한다(Graceful Shutdown).
        """
        while True:
            try:
                await self._drain_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - 폴링 루프는 예외로 죽으면 안 된다
                logger.exception("Outbox 폴링 실패")
            await asyncio.sleep(POLL_INTERVAL_SECONDS)

    async def _drain_once(self) -> None:
        queue_url = SqsConfig().domain_event_queue_url  # type: ignore[call-arg]

        async with self._session_factory() as session:
            stmt = (
                select(OutboxModel)
                .where(OutboxModel.processed.is_(False))
                .order_by(OutboxModel.created_at)
                .limit(BATCH_SIZE)
            )
            rows = (await session.execute(stmt)).scalars().all()
            if not rows:
                return

            async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:  # type: ignore[call-arg]
                for row in rows:
                    try:
                        # 이 Outbox 행의 고유 event_id를 payload에 실어 보낸다 — 핸들러가 이
                        # 값을 Ledger 키로 써서 재시도(at-least-once)로 인한 중복 처리를
                        # 스스로 건너뛸 수 있다(domain-events.md "이벤트 핸들러 멱등성" 참고).
                        body = json.loads(row.payload)
                        body["outbox_event_id"] = row.event_id
                        await sqs_client.send_message(
                            QueueUrl=queue_url,
                            MessageBody=json.dumps(body),
                            MessageAttributes={"eventType": {"DataType": "String", "StringValue": row.event_type}},
                        )
                        row.processed = True
                    except Exception:  # noqa: BLE001 - 발행 실패 행은 다음 tick에서 재시도
                        logger.exception("SQS 발행 실패: event_type=%s event_id=%s", row.event_type, row.event_id)

            await session.commit()
