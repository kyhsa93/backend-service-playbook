from __future__ import annotations

from datetime import date, datetime, time

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ...domain.refund_reason_insights_query import RefundReasonCategoryCount, RefundReasonInsightsQuery
from .refund_repository import RefundModel


class SqlAlchemyRefundReasonInsightsQuery(RefundReasonInsightsQuery):
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def get_insights(
        self, from_date: date | None = None, to_date: date | None = None
    ) -> list[RefundReasonCategoryCount]:
        stmt = (
            select(RefundModel.reason_category, func.count())
            .where(RefundModel.deleted_at.is_(None))
            .where(RefundModel.reason_category.is_not(None))
        )
        if from_date:
            stmt = stmt.where(RefundModel.created_at >= datetime.combine(from_date, time.min))
        if to_date:
            stmt = stmt.where(RefundModel.created_at <= datetime.combine(to_date, time.max))

        rows = (await self._session.execute(stmt.group_by(RefundModel.reason_category))).all()
        return [RefundReasonCategoryCount(category=category, count=count) for category, count in rows]
