from __future__ import annotations

from ...application.command.send_monthly_card_statement_handler import SendMonthlyCardStatementHandler


class CardTaskController:
    """A Task Controller — an input adapter of the Interface layer (scheduling.md). The
    same principle as account/interface/task/account_task_controller.py: only delegates,
    with no logic, letting exceptions propagate as-is (TaskConsumer decides retry/DLQ)."""

    def __init__(self, handler: SendMonthlyCardStatementHandler) -> None:
        self._handler = handler

    async def send_monthly_statement(self, payload: dict) -> None:
        del payload  # this Task has no payload (the scheduler enqueues it with {}) — the past month
        # is computed based on the current date
        await self._handler.execute()
