from __future__ import annotations

from ...domain.repository import AccountRepository
from ...domain.spending_analysis_repository import SpendingAnalysisRepository
from ...domain.spending_forecast import SpendingForecast
from ...domain.spending_forecast_repository import SpendingForecastRepository
from ..service.spending_forecast_model import SpendingForecastModel, SpendingHistoryPoint

PAGE_SIZE = 200

# A cold-start guard, not a tuning knob: 2 points make any line "fit" perfectly (r_squared ==
# 1 regardless of the actual trend), so 3 is the minimum for SpendingForecastModel's R² to mean
# anything. An account younger than 3 analyzed months is simply skipped and retried by next
# month's run once it has more history — the same "skip, don't fail" idempotency-adjacent
# posture as AnalyzeMonthlySpendingHandler skipping an already-analyzed account.
MIN_HISTORY_MONTHS_FOR_FORECAST = 3
MAX_HISTORY_MONTHS_FOR_FORECAST = 6


class ForecastSpendingHandler:
    """The Command Service for the monthly spending-forecast batch, delegated to by
    AccountTaskController.forecast_spending() (account.forecast-spending). Since this is a
    system-driven use case triggered once a month by the Task Queue, there is no Command
    dataclass representing a user request (the same reason as AnalyzeMonthlySpendingHandler)
    — forecast_month is instead the one piece of state the Scheduler fixed at enqueue time.

    Trains (fits) a fresh SpendingForecastModel per account from that account's own
    spending_analysis history on every run — there's no persisted "model weights" row separate
    from the forecast itself, the same simplicity tradeoff the ETL job upstream makes
    (recomputed monthly, not maintained incrementally). Output is a queryable read-model row,
    not a file.
    """

    def __init__(
        self,
        repo: AccountRepository,
        spending_analysis_repo: SpendingAnalysisRepository,
        spending_forecast_repo: SpendingForecastRepository,
        spending_forecast_model: SpendingForecastModel,
    ) -> None:
        self._repo = repo
        self._spending_analysis_repo = spending_analysis_repo
        self._spending_forecast_repo = spending_forecast_repo
        self._spending_forecast_model = spending_forecast_model

    async def execute(self, forecast_month: str) -> int:
        forecasted_count = 0
        page = 0

        while True:
            accounts, total = await self._repo.find_accounts(page=page, take=PAGE_SIZE, status=["ACTIVE"])
            if not accounts:
                break

            for account in accounts:
                already_forecasted = await self._spending_forecast_repo.has_forecast(account.account_id, forecast_month)
                if already_forecasted:
                    continue

                history = await self._spending_analysis_repo.find_recent_analyses(
                    account.account_id, forecast_month, MAX_HISTORY_MONTHS_FOR_FORECAST
                )
                if len(history) < MIN_HISTORY_MONTHS_FOR_FORECAST:
                    continue

                prediction = self._spending_forecast_model.predict(
                    [
                        SpendingHistoryPoint(analysis_month=analysis.analysis_month, total_amount=analysis.total_amount)
                        for analysis in history
                    ]
                )

                forecast = SpendingForecast.create(
                    account_id=account.account_id,
                    forecast_month=forecast_month,
                    predicted_amount=prediction.predicted_amount,
                    confidence=prediction.confidence,
                    history_months_used=len(history),
                )
                await self._spending_forecast_repo.save_forecast(forecast)
                forecasted_count += 1

            if (page + 1) * PAGE_SIZE >= total:
                break
            page += 1

        return forecasted_count
