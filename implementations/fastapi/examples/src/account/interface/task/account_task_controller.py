from __future__ import annotations

from ....config.interest_config import InterestConfig
from ...application.command.apply_daily_interest_handler import ApplyDailyInterestHandler


class AccountTaskController:
    """Task Controller — Interface 레이어의 입력 어댑터(scheduling.md). HTTP Router가 HTTP
    요청을 받아 Command Handler에 위임하듯, 이 클래스는 TaskConsumer가 SQS에서 수신한
    Task 메시지를 받아 Command Service(ApplyDailyInterestHandler)를 호출한다.

    로직 없이 위임만 한다 — 조건 분기·비즈니스 규칙을 넣지 않는다. 예외를 그대로 던진다
    (catch하지 않는다) — TaskConsumer가 재시도/DLQ 여부를 결정해야 하므로 여기서 삼키면
    안 된다(scheduling.md "Task Controller — Interface 레이어" 원칙).
    """

    def __init__(self, handler: ApplyDailyInterestHandler) -> None:
        self._handler = handler

    async def apply_daily_interest(self, payload: dict) -> None:
        del payload  # 이 Task는 페이로드가 없다(scheduler가 {}로 enqueue) — 그날 날짜 기준으로 실행한다
        await self._handler.execute(InterestConfig().daily_rate)
