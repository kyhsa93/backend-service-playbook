from __future__ import annotations

import json
import logging
from collections.abc import Awaitable, Callable

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .outbox_model import OutboxModel

logger = logging.getLogger(__name__)


class OutboxRelay:
    """Outbox 테이블의 미처리(`processed=False`) 행을 모두 드레인한다.

    커맨드 처리마다 — 자신이 방금 적재한 이벤트인지 여부와 무관하게 — 테이블 전체를 훑는다.
    핸들러가 실패해도 예외를 밖으로 던지지 않고 로깅만 하며, 실패한 행은 `processed=False`로
    남겨두어 다음 커맨드가 트리거하는 드레인에서 자동으로 재시도된다(at-least-once).
    """

    def __init__(self, session: AsyncSession, handlers: dict[str, Callable[[dict], Awaitable[None]]]) -> None:
        self._session = session
        self._handlers = handlers

    async def process_pending(self) -> None:
        stmt = select(OutboxModel).where(OutboxModel.processed.is_(False))
        rows = (await self._session.execute(stmt)).scalars().all()

        for row in rows:
            handler = self._handlers.get(row.event_type)
            try:
                if handler is not None:
                    await handler(json.loads(row.payload))
                row.processed = True
            except Exception:  # noqa: BLE001 - 실패한 이벤트는 다음 드레인에서 재시도해야 하므로 여기서 삼킨다
                logger.exception(
                    "이벤트 처리 실패: event_type=%s event_id=%s", row.event_type, row.event_id
                )

        await self._session.flush()
