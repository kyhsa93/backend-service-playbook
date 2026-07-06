from __future__ import annotations

import dataclasses
import json
import uuid
from collections.abc import Sequence

from sqlalchemy.ext.asyncio import AsyncSession

from .outbox_model import OutboxModel


class OutboxWriter:
    """Aggregate가 `pull_events()`로 꺼낸 Domain Event를 Outbox 테이블에 적재한다.

    Repository의 save() 메서드가 Aggregate 상태 저장과 같은 `AsyncSession`으로 호출하므로,
    Aggregate 저장과 Outbox 적재가 하나의 트랜잭션으로 묶여 원자적으로 커밋된다.
    """

    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def save_all(self, events: Sequence[object]) -> None:
        for event in events:
            self._session.add(OutboxModel(
                event_id=uuid.uuid4().hex,
                event_type=type(event).__name__,
                payload=json.dumps(dataclasses.asdict(event), default=str),
                processed=False,
            ))
