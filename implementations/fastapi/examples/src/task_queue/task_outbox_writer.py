from __future__ import annotations

import json
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from .task_outbox_model import TaskOutboxModel


class TaskOutboxWriter:
    """Task를 `task_outbox` 테이블에 적재한다 — Scheduler(Cron)가 호출하는 유일한
    메서드다. Scheduler는 이 메서드를 호출해 커밋하는 것 말고는 아무것도 하지 않는다
    (scheduling.md "Scheduler 역할 분리": enqueue만, 비즈니스 로직 실행 금지).

    Domain Event의 `OutboxWriter`(src/outbox/outbox_writer.py)와 테이블·큐가 분리되어
    있다는 점만 다르고 "쓰기와 같은 트랜잭션 안에서 적재 → 별도 Poller가 발행"이라는
    구조는 동일하다.
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
