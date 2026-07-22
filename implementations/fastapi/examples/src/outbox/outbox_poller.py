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
    """Reads the Outbox table (`processed=False`) and is responsible only for publishing to
    SQS ("carries events piled up in the DB over to the queue"). It never calls any
    EventHandler directly — that's OutboxConsumer's job.

    A Command Handler never references this class at all — if it did, the harness's
    outbox-no-sync-drain rule would catch it (domain-events.md). This class running
    independently and periodically via `asyncio.create_task()` at app startup is itself the
    key to eliminating "synchronous draining in the same process right after saving" — even
    after a Command Handler commits the save and returns its response, exactly when this
    event goes out to the queue isn't known until the next tick (up to 1 second later).

    `processed=True` now means "delivery to SQS is complete," not "the handler finished
    processing it" — from this point on, retry/at-least-once guarantees are the
    responsibility of SQS's visibility timeout + DLQ, not the outbox table (see
    docs/architecture/domain-events.md).
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        # The same convention as SesNotificationService in notification_service.py — the
        # boto3 Session is created only once, and the actual client is opened per call (in
        # a short async with block).
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        """A background loop started by `main.py`'s lifespan via `asyncio.create_task()`.
        If the Task is cancelled (`asyncio.CancelledError`), it's propagated as-is, ending
        the loop — if there's an in-progress tick's drain, it's not finished, but stopped
        immediately (Graceful Shutdown).
        """
        while True:
            try:
                await self._drain_once()
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 - the polling loop must never die from an exception
                logger.exception("Outbox polling failed")
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
                        # Carries this Outbox row's unique event_id in the payload — the
                        # handler can use this value as a Ledger key to skip duplicate
                        # processing caused by a retry (at-least-once) on its own (see
                        # "Event Handler Idempotency" in domain-events.md).
                        body = json.loads(row.payload)
                        body["outbox_event_id"] = row.event_id
                        await sqs_client.send_message(
                            QueueUrl=queue_url,
                            MessageBody=json.dumps(body),
                            MessageAttributes={"eventType": {"DataType": "String", "StringValue": row.event_type}},
                        )
                        row.processed = True
                    except Exception:  # noqa: BLE001 - a row that fails to publish is retried on the next tick
                        logger.exception(
                            "Failed to publish to SQS: event_type=%s event_id=%s", row.event_type, row.event_id
                        )

            await session.commit()
