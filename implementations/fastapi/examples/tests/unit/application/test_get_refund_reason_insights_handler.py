from datetime import date
from unittest.mock import AsyncMock

import pytest

from src.payment.application.query.get_refund_reason_insights_handler import (
    GetRefundReasonInsightsHandler,
    GetRefundReasonInsightsQuery,
)
from src.payment.domain.refund_reason_insights_query import RefundReasonCategoryCount


@pytest.fixture
def insights_query() -> AsyncMock:
    return AsyncMock()


@pytest.mark.asyncio
async def test_execute_returns_counts_per_category_and_the_summed_total(insights_query: AsyncMock) -> None:
    insights_query.get_insights.return_value = [
        RefundReasonCategoryCount(category="DEFECTIVE_PRODUCT", count=3),
        RefundReasonCategoryCount(category="OTHER", count=2),
    ]
    handler = GetRefundReasonInsightsHandler(insights_query)

    result = await handler.execute(GetRefundReasonInsightsQuery())

    assert result.total_classified == 5
    assert [(c.category, c.count) for c in result.counts] == [("DEFECTIVE_PRODUCT", 3), ("OTHER", 2)]


@pytest.mark.asyncio
async def test_execute_passes_the_date_range_through_to_the_query(insights_query: AsyncMock) -> None:
    insights_query.get_insights.return_value = []
    handler = GetRefundReasonInsightsHandler(insights_query)

    await handler.execute(GetRefundReasonInsightsQuery(from_date=date(2026, 7, 1), to_date=date(2026, 7, 31)))

    insights_query.get_insights.assert_awaited_once_with(date(2026, 7, 1), date(2026, 7, 31))


@pytest.mark.asyncio
async def test_execute_when_nothing_is_classified_yet_then_total_classified_is_zero(insights_query: AsyncMock) -> None:
    insights_query.get_insights.return_value = []
    handler = GetRefundReasonInsightsHandler(insights_query)

    result = await handler.execute(GetRefundReasonInsightsQuery())

    assert result.total_classified == 0
    assert result.counts == []
