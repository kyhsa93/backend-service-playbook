from datetime import datetime
from unittest.mock import AsyncMock

import pytest

from src.account.application.command.analyze_monthly_spending_handler import (
    AnalyzeMonthlySpendingHandler,
    spending_analysis_period_range,
)
from src.account.domain.account import Account

ANALYSIS_MONTH = "2026-07"


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def analysis_repo() -> AsyncMock:
    mock = AsyncMock()
    mock.has_analysis.return_value = False
    return mock


def _active_account() -> Account:
    return Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")


def test_spending_analysis_period_range_derives_month_and_previous_month_boundaries_from_the_period_string() -> None:
    month_start, month_end, previous_month_start, previous_month_end = spending_analysis_period_range("2026-07")

    assert month_start == datetime(2026, 7, 1)
    assert month_end == datetime(2026, 8, 1)
    assert previous_month_start == datetime(2026, 6, 1)
    assert previous_month_end == datetime(2026, 7, 1)


def test_spending_analysis_period_range_january_rolls_back_to_december_of_the_previous_year() -> None:
    month_start, month_end, previous_month_start, previous_month_end = spending_analysis_period_range("2026-01")

    assert month_start == datetime(2026, 1, 1)
    assert month_end == datetime(2026, 2, 1)
    assert previous_month_start == datetime(2025, 12, 1)
    assert previous_month_end == datetime(2026, 1, 1)


@pytest.mark.asyncio
async def test_execute_analyzes_every_active_account_and_saves_the_computed_analysis(repo, analysis_repo) -> None:
    account1 = _active_account()
    account2 = _active_account()
    repo.find_accounts.side_effect = [([account1, account2], 2), ([], 2)]
    repo.summarize_transactions.side_effect = [
        (2, 50000),  # account1 current month
        (0, 0),  # account1 previous month
        (1, 10000),  # account2 current month
        (1, 20000),  # account2 previous month
    ]
    handler = AnalyzeMonthlySpendingHandler(repo, analysis_repo)

    analyzed_count = await handler.execute(ANALYSIS_MONTH)

    assert analyzed_count == 2
    assert analysis_repo.save_analysis.await_count == 2
    saved_analyses = [call.args[0] for call in analysis_repo.save_analysis.await_args_list]
    assert saved_analyses[0].account_id == account1.account_id
    assert saved_analyses[0].total_amount == 50000
    assert saved_analyses[0].transaction_count == 2
    assert saved_analyses[0].change_from_previous_month == 100  # previous total is 0, current > 0
    assert saved_analyses[0].trend == "INCREASING"
    assert saved_analyses[1].account_id == account2.account_id
    assert saved_analyses[1].total_amount == 10000
    assert saved_analyses[1].change_from_previous_month == -50
    assert saved_analyses[1].trend == "DECREASING"

    month_start, month_end, previous_month_start, previous_month_end = spending_analysis_period_range(ANALYSIS_MONTH)
    repo.summarize_transactions.assert_any_call(account1.account_id, ["WITHDRAWAL"], month_start, month_end)
    repo.summarize_transactions.assert_any_call(
        account1.account_id, ["WITHDRAWAL"], previous_month_start, previous_month_end
    )


@pytest.mark.asyncio
async def test_execute_skips_an_account_already_analyzed_for_the_month(repo, analysis_repo) -> None:
    account = _active_account()
    repo.find_accounts.side_effect = [([account], 1), ([], 1)]
    analysis_repo.has_analysis.return_value = True
    handler = AnalyzeMonthlySpendingHandler(repo, analysis_repo)

    analyzed_count = await handler.execute(ANALYSIS_MONTH)

    assert analyzed_count == 0
    repo.summarize_transactions.assert_not_called()
    analysis_repo.save_analysis.assert_not_called()


@pytest.mark.asyncio
async def test_execute_saves_nothing_when_there_are_no_active_accounts(repo, analysis_repo) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = AnalyzeMonthlySpendingHandler(repo, analysis_repo)

    analyzed_count = await handler.execute(ANALYSIS_MONTH)

    assert analyzed_count == 0
    analysis_repo.save_analysis.assert_not_called()
