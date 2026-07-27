from datetime import datetime

from sqlalchemy import UniqueConstraint, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Mapped, mapped_column

from ...domain.spending_analysis import SpendingAnalysis
from ...domain.spending_analysis_repository import SpendingAnalysisRepository
from .account_repository import Base


class SpendingAnalysisModel(Base):
    """The read-model table account.analyze-monthly-spending's ETL writes to, one row per
    (account_id, analysis_month) — the unique constraint is the idempotency backstop, the
    same role as sent_statement_emails' (card_id, ...) uniqueness and
    transactions.idx_transactions_reference_id_type.
    """

    __tablename__ = "spending_analysis"
    __table_args__ = (
        UniqueConstraint("account_id", "analysis_month", name="uq_spending_analysis_account_id_analysis_month"),
    )

    analysis_id: Mapped[str] = mapped_column(primary_key=True)
    account_id: Mapped[str] = mapped_column(index=True)
    analysis_month: Mapped[str]
    total_amount: Mapped[int]
    transaction_count: Mapped[int]
    average_amount: Mapped[int]
    change_from_previous_month: Mapped[int]
    trend: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(default=datetime.utcnow)


class SqlAlchemySpendingAnalysisRepository(SpendingAnalysisRepository):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def find_analysis(self, account_id: str, analysis_month: str) -> SpendingAnalysis | None:
        stmt = select(SpendingAnalysisModel).where(
            SpendingAnalysisModel.account_id == account_id,
            SpendingAnalysisModel.analysis_month == analysis_month,
        )
        row = (await self._session.execute(stmt)).scalar_one_or_none()
        if row is None:
            return None
        return self._to_domain(row)

    async def has_analysis(self, account_id: str, analysis_month: str) -> bool:
        stmt = (
            select(func.count())
            .select_from(SpendingAnalysisModel)
            .where(
                SpendingAnalysisModel.account_id == account_id,
                SpendingAnalysisModel.analysis_month == analysis_month,
            )
        )
        count = (await self._session.execute(stmt)).scalar_one()
        return count > 0

    async def save_analysis(self, analysis: SpendingAnalysis) -> None:
        self._session.add(
            SpendingAnalysisModel(
                analysis_id=analysis.analysis_id,
                account_id=analysis.account_id,
                analysis_month=analysis.analysis_month,
                total_amount=analysis.total_amount,
                transaction_count=analysis.transaction_count,
                average_amount=analysis.average_amount,
                change_from_previous_month=analysis.change_from_previous_month,
                trend=analysis.trend,
                created_at=analysis.created_at,
            )
        )
        await self._session.flush()

    def _to_domain(self, row: SpendingAnalysisModel) -> SpendingAnalysis:
        return SpendingAnalysis(
            analysis_id=row.analysis_id,
            account_id=row.account_id,
            analysis_month=row.analysis_month,
            total_amount=row.total_amount,
            transaction_count=row.transaction_count,
            average_amount=row.average_amount,
            change_from_previous_month=row.change_from_previous_month,
            trend=row.trend,  # type: ignore[arg-type]
            created_at=row.created_at,
        )
