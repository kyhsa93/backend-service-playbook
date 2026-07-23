from __future__ import annotations

import dataclasses
import json
import uuid
from collections.abc import Sequence

from opentelemetry.propagate import inject
from sqlalchemy.ext.asyncio import AsyncSession

from .outbox_model import OutboxModel


class OutboxWriter:
    """Loads the Domain Events an Aggregate pulled via `pull_events()` into the Outbox table.

    Since the Repository's save() method is called with the same `AsyncSession` as the
    Aggregate-state save, the Aggregate save and the Outbox load are bundled into a single
    transaction and committed atomically.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_all(self, events: Sequence[object]) -> None:
        for event in events:
            # An Integration Event uses its versioned public contract name (event_name,
            # e.g. 'account.suspended.v1') as event_type. A Domain Event has no event_name,
            # so its class name is used as-is.
            event_type = getattr(event, "event_name", None) or type(event).__name__

            # Captures the W3C traceparent of whichever span is active right now (the HTTP
            # request that triggered this Command, in practice) so OutboxPoller/OutboxConsumer
            # can carry it across the Outbox's async boundary (observability.md). `inject()`
            # writes nothing into `carrier` when there's no active span (e.g. a unit test
            # calling this directly), leaving trace_parent as None.
            carrier: dict[str, str] = {}
            inject(carrier)

            self._session.add(
                OutboxModel(
                    event_id=uuid.uuid4().hex,
                    event_type=event_type,
                    payload=json.dumps(dataclasses.asdict(event), default=str),
                    processed=False,
                    trace_parent=carrier.get("traceparent"),
                )
            )
