from dataclasses import dataclass

from ...domain.errors import AccountNotFoundError, SpendingForecastNotFoundError
from ...domain.repository import AccountQuery
from ...domain.spending_forecast_repository import SpendingForecastQuery
from .result import SpendingForecastResult


@dataclass
class GetSpendingForecastQuery:
    account_id: str
    requester_id: str
    forecast_month: str


class GetSpendingForecastHandler:
    """Verifies account ownership by reusing AccountQuery.find_accounts — the same helper
    GetSpendingAnalysisHandler already uses, since Account (like Refund) has an owner_id
    column directly on it, so ownership is a single lookup, not a two-hop verification. This
    handler never trains a model itself — it only serves the precomputed row
    ForecastSpendingHandler already wrote (query endpoints never live-compute on request).
    """

    def __init__(self, account_query: AccountQuery, forecast_query: SpendingForecastQuery) -> None:
        self._account_query = account_query
        self._forecast_query = forecast_query

    async def execute(self, query: GetSpendingForecastQuery) -> SpendingForecastResult:
        accounts, _ = await self._account_query.find_accounts(
            page=0, take=1, account_id=query.account_id, owner_id=query.requester_id
        )
        account = accounts[0] if accounts else None
        if account is None:
            raise AccountNotFoundError(query.account_id)

        forecast = await self._forecast_query.find_forecast(query.account_id, query.forecast_month)
        if forecast is None:
            raise SpendingForecastNotFoundError(query.account_id, query.forecast_month)

        return SpendingForecastResult(
            forecast_month=forecast.forecast_month,
            predicted_amount=forecast.predicted_amount,
            confidence=forecast.confidence,
            history_months_used=forecast.history_months_used,
            created_at=forecast.created_at,
        )
