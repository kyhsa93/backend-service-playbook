from __future__ import annotations

import json
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from .task_outbox_model import TaskOutboxModel


class TaskOutboxWriter:
    """Loads a Task into the `task_outbox` table — the only method the Scheduler (Cron)
    calls. The Scheduler does nothing beyond calling this method and committing (see
    "Separating the Scheduler's role" in scheduling.md: enqueue only, never execute
    business logic).

    Differs from the Domain Event's `OutboxWriter` (src/outbox/outbox_writer.py) only in
    that the table and queue are separate — the structure "load within the same
    transaction as the write → a separate Poller publishes it" is identical.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def enqueue(self, task_type: str, payload: dict, group_id: str, deduplication_id: str) -> None:
        self._session.add(
            TaskOutboxModel(
                task_id=uuid.uuid4().hex,
                task_type=task_type,
                payload=json.dumps(payload, default=str),
                group_id=group_id,
                deduplication_id=deduplication_id,
                processed=False,
            )
        )
