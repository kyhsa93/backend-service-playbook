from abc import ABC, abstractmethod

from .spending_forecast import SpendingForecast


class SpendingForecastQuery(ABC):
    """A read-only interface for the spending_forecast read model — the same
    Query/Repository split as SpendingAnalysisQuery/SpendingAnalysisRepository. Never exposes
    a write method such as save_forecast() (see cqrs-pattern.md).
    """

    @abstractmethod
    async def find_forecast(self, account_id: str, forecast_month: str) -> SpendingForecast | None: ...

    # A cheap idempotency check ahead of the real work — the (account_id, forecast_month)
    # unique constraint on the table is the last line of defense, the same two-layer pattern
    # as SpendingAnalysisRepository.has_analysis.
    @abstractmethod
    async def has_forecast(self, account_id: str, forecast_month: str) -> bool: ...


class SpendingForecastRepository(SpendingForecastQuery, ABC):
    """The write model — extends SpendingForecastQuery to reuse its lookup methods, adding
    only save_forecast()."""

    @abstractmethod
    async def save_forecast(self, forecast: SpendingForecast) -> None: ...
