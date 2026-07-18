from __future__ import annotations

import json
import logging
from collections.abc import Awaitable, Callable

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from .outbox_model import OutboxModel

logger = logging.getLogger(__name__)


MAX_PASSES = 10


class OutboxRelay:
    """Outbox 테이블의 미처리(`processed=False`) 행을 모두 드레인한다.

    커맨드 처리마다 — 자신이 방금 적재한 이벤트인지 여부와 무관하게 — 테이블 전체를 훑는다.
    핸들러 실행 도중 새 행이 적재될 수 있으므로(예: AccountSuspended Domain Event 핸들러가
    account.suspended.v1 Integration Event를 같은 세션에 적재) 더 이상 진전이 없을 때까지
    여러 패스로 반복 드레인한다 — 이렇게 하면 Domain Event → Integration Event → 외부 BC
    수신이 한 커맨드 처리 안에서 완결된다.

    핸들러가 실패해도 예외를 밖으로 던지지 않고 로깅만 하며, 실패한 행은 `processed=False`로
    남겨두어 다음 커맨드가 트리거하는 드레인에서 자동으로 재시도된다(at-least-once). 단, 이번
    호출 안에서 이미 실패한 행은 같은 호출의 다음 패스에서 다시 시도하지 않는다 — 실패 재시도는
    다음 `process_pending()` 호출의 몫이다.
    """

    def __init__(self, session: AsyncSession, handlers: dict[str, Callable[[dict], Awaitable[None]]]) -> None:
        self._session = session
        self._handlers = handlers

    async def process_pending(self) -> None:
        failed_in_this_run: set[str] = set()

        for _ in range(MAX_PASSES):
            stmt = select(OutboxModel).where(OutboxModel.processed.is_(False))
            rows = [
                row
                for row in (await self._session.execute(stmt)).scalars().all()
                if row.event_id not in failed_in_this_run
            ]
            if not rows:
                return

            progressed = 0
            for row in rows:
                handler = self._handlers.get(row.event_type)
                try:
                    if handler is not None:
                        # 이벤트 자체의 payload에 이 Outbox 행의 고유 event_id를 실어 보낸다 —
                        # 핸들러가 이 값을 Ledger 키로 써서 재시도(at-least-once)로 인한 중복
                        # 처리를 스스로 건너뛸 수 있다(domain-events.md "이벤트 핸들러 멱등성" 참고).
                        payload = json.loads(row.payload)
                        payload["outbox_event_id"] = row.event_id
                        await handler(payload)
                    row.processed = True
                    progressed += 1
                except Exception:  # noqa: BLE001 - 실패한 이벤트는 다음 드레인에서 재시도해야 하므로 여기서 삼킨다
                    failed_in_this_run.add(row.event_id)
                    logger.exception("이벤트 처리 실패: event_type=%s event_id=%s", row.event_type, row.event_id)

            await self._session.flush()
            if progressed == 0:  # 이번 패스에서 진전이 없었다면 더 반복해도 소용없다
                return
