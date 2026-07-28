from __future__ import annotations

from ....config.interest_config import InterestConfig
from ...application.command.analyze_monthly_spending_handler import AnalyzeMonthlySpendingHandler
from ...application.command.apply_daily_interest_handler import ApplyDailyInterestHandler
from ...application.command.forecast_spending_handler import ForecastSpendingHandler


class AccountTaskController:
    """A Task Controller — an input adapter of the Interface layer (scheduling.md). Just as
    an HTTP Router receives an HTTP request and delegates to a Command Handler, this class
    receives the Task message TaskConsumer received from SQS and calls the Command Service
    (ApplyDailyInterestHandler/AnalyzeMonthlySpendingHandler).

    Only delegates, with no logic — no conditional branching or business rules are added
    here. Exceptions are thrown as-is (never caught) — since TaskConsumer must decide
    retry/DLQ, they must never be swallowed here (the "Task Controller — Interface layer"
    principle in scheduling.md).
    """

    def __init__(
        self,
        handler: ApplyDailyInterestHandler,
        analyze_monthly_spending_handler: AnalyzeMonthlySpendingHandler,
        forecast_spending_handler: ForecastSpendingHandler,
    ) -> None:
        self._handler = handler
        self._analyze_monthly_spending_handler = analyze_monthly_spending_handler
        self._forecast_spending_handler = forecast_spending_handler

    async def apply_daily_interest(self, payload: dict) -> None:
        del payload  # this Task has no payload (the scheduler enqueues it with {}) — it runs based on the current date
        await self._handler.execute(InterestConfig().daily_rate)

    async def analyze_monthly_spending(self, payload: dict) -> None:
        await self._analyze_monthly_spending_handler.execute(payload["analysis_month"])

    async def forecast_spending(self, payload: dict) -> None:
        await self._forecast_spending_handler.execute(payload["forecast_month"])
