from datetime import datetime

from sqlalchemy import UniqueConstraint, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ....common.clock import utc_now
from ...domain.spending_forecast import SpendingForecast
from ...domain.spending_forecast_repository import SpendingForecastRepository
from .account_repository import Base


class SpendingForecastModel(Base):
    """The read-model table account.forecast-spending's ETL writes to, one row per
    (account_id, forecast_month) — the unique constraint is the idempotency backstop, the same
    role as spending_analysis's (account_id, analysis_month) constraint.
    """

    __tablename__ = "spending_forecast"
    __table_args__ = (
        UniqueConstraint("account_id", "forecast_month", name="uq_spending_forecast_account_id_forecast_month"),
    )

    forecast_id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str] = mapped_column(index=True)
    forecast_month: Mapped[str]
    predicted_amount: Mapped[int]
    confidence: Mapped[str]
    history_months_used: Mapped[int]
    created_at: Mapped[datetime] = mapped_column(default=utc_now)


class SqlAlchemySpendingForecastRepository(SpendingForecastRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_forecast(self, account_id: str, forecast_month: str) -> SpendingForecast | None:
        stmt = select(SpendingForecastModel).where(
            SpendingForecastModel.account_id == account_id,
            SpendingForecastModel.forecast_month == forecast_month,
        )
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        if row is None:
            return None
        return self._to_domain(row)

    async def has_forecast(self, account_id: str, forecast_month: str) -> bool:
        stmt = (
            select(func.count())
            .select_from(SpendingForecastModel)
            .where(
                SpendingForecastModel.account_id == account_id,
                SpendingForecastModel.forecast_month == forecast_month,
            )
        )
        count = (await self._session.execute(stmt)).scalar_one()
        return count > 0

    async def save_forecast(self, forecast: SpendingForecast) -> None:
        self._session.add(
            SpendingForecastModel(
                forecast_id=forecast.forecast_id,
                account_id=forecast.account_id,
                forecast_month=forecast.forecast_month,
                predicted_amount=forecast.predicted_amount,
                confidence=forecast.confidence,
                history_months_used=forecast.history_months_used,
                created_at=forecast.created_at,
            )
        )
        await self._session.flush()

    def _to_domain(self, row: SpendingForecastModel) -> SpendingForecast:
        return SpendingForecast(
            forecast_id=row.forecast_id,
            account_id=row.account_id,
            forecast_month=row.forecast_month,
            predicted_amount=row.predicted_amount,
            confidence=row.confidence,  # type: ignore[arg-type]
            history_months_used=row.history_months_used,
            created_at=row.created_at,
        )
