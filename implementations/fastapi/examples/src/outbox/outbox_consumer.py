from __future__ import annotations

import asyncio
import json
import logging

import aioboto3
from opentelemetry import trace
from opentelemetry.propagate import extract
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from ..config.aws_config import AwsConfig
from ..config.sqs_config import SqsConfig
from .event_handlers import build_event_handlers

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


class OutboxConsumer:
    """Waits to receive from SQS via long polling (`receive_message`'s
    `WaitTimeSeconds`), and once a message is received, looks up the handler in the dict
    `build_event_handlers()` assembled by `eventType` (MessageAttributes) and calls it —
    whether it's a Domain Event Handler (application/event/) or an Integration Event
    Controller (interface/integration_event/), it goes through this single Consumer.

    Handler success → deleted (ack) via `delete_message`. Handler failure (or no
    registered handler) → not deleted — SQS automatically redelivers it after the
    visibility timeout (at-least-once). The EventHandler idempotency this repository
    requires (domain-events.md) is premised exactly on this redelivery.
    """

    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory
        self._boto_session = aioboto3.Session()

    async def run_forever(self) -> None:
        """A background loop started by `main.py`'s lifespan via `asyncio.create_task()`.
        If the Task is cancelled, an in-progress `receive_message` wait (up to
        `WaitTimeSeconds`) ends immediately and it shuts down (Graceful Shutdown) —
        `sqs_client` is cleaned up as it exits the `async with` block.
        """
        queue_url = SqsConfig().domain_event_queue_url  # type: ignore[call-arg]
        async with self._boto_session.client("sqs", **AwsConfig().client_kwargs()) as sqs_client:  # type: ignore[call-arg]
            while True:
                try:
                    result = await sqs_client.receive_message(
                        QueueUrl=queue_url,
                        MaxNumberOfMessages=10,
                        MessageAttributeNames=["eventType", "traceparent"],
                        WaitTimeSeconds=5,
                    )
                except asyncio.CancelledError:
                    raise
                except Exception:  # noqa: BLE001 - the receive loop must never die from an exception
                    logger.exception("Failed to receive from SQS")
                    await asyncio.sleep(1)
                    continue

                for message in result.get("Messages", []):
                    await self._handle_message(sqs_client, queue_url, message)

    async def _handle_message(self, sqs_client, queue_url: str, message: dict) -> None:
        attributes = message.get("MessageAttributes", {})
        event_type = attributes.get("eventType", {}).get("StringValue")
        trace_parent = attributes.get("traceparent", {}).get("StringValue")
        try:
            if not event_type:
                raise ValueError("The eventType message attribute is missing.")

            # Re-hydrates the originating HTTP request's trace context (set by
            # OutboxWriter, forwarded by OutboxPoller) and processes the event as a child
            # span of it — absent a traceparent (e.g. no OTLP configured when the row was
            # written, or a row written outside any request), `extract({})` just yields the
            # current (empty) context, and a new, unlinked trace is started instead
            # (observability.md).
            context = extract({"traceparent": trace_parent}) if trace_parent else None
            with tracer.start_as_current_span("outbox.process_event", context=context):
                payload = json.loads(message.get("Body", "{}"))
                async with self._session_factory() as session:
                    handler = build_event_handlers(session).get(event_type)
                    if handler is None:
                        raise ValueError(f"No registered handler: {event_type}")
                    await handler(payload)
                    await session.commit()

            await sqs_client.delete_message(QueueUrl=queue_url, ReceiptHandle=message["ReceiptHandle"])
        except asyncio.CancelledError:
            raise
        except Exception:  # noqa: BLE001 - it must not be deleted, so it's retried after the visibility timeout
            logger.exception("Failed to process event: event_type=%s", event_type)
