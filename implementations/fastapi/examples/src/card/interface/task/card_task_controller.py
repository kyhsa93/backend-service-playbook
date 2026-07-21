from __future__ import annotations

from ...application.command.send_monthly_card_statement_handler import SendMonthlyCardStatementHandler


class CardTaskController:
    """Task Controller — Interface 레이어의 입력 어댑터(scheduling.md).
    account/interface/task/account_task_controller.py와 동일한 원칙: 로직 없이 위임만,
    예외는 그대로 전파(TaskConsumer가 재시도/DLQ를 결정)."""

    def __init__(self, handler: SendMonthlyCardStatementHandler) -> None:
        self._handler = handler

    async def send_monthly_statement(self, payload: dict) -> None:
        del payload  # 이 Task는 페이로드가 없다(scheduler가 {}로 enqueue) — 그날 날짜 기준으로 지난 달을 계산한다
        await self._handler.execute()
