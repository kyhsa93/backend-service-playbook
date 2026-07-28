from unittest.mock import AsyncMock, MagicMock

import pytest

from src.account.application.command.forecast_spending_handler import ForecastSpendingHandler
from src.account.application.service.spending_forecast_model import SpendingForecastPrediction
from src.account.domain.account import Account
from src.account.domain.spending_analysis import SpendingAnalysis

FORECAST_MONTH = "2026-07"


@pytest.fixture
def repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def spending_analysis_repo() -> AsyncMock:
    return AsyncMock()


@pytest.fixture
def spending_forecast_repo() -> AsyncMock:
    mock = AsyncMock()
    mock.has_forecast.return_value = False
    return mock


@pytest.fixture
def spending_forecast_model() -> MagicMock:
    # SpendingForecastModel.predict is a plain (non-async) method — a synchronous computation
    # with no I/O — so this must be a MagicMock, not an AsyncMock (which would make
    # predict(...) return a coroutine instead of the prediction value).
    return MagicMock()


def _active_account() -> Account:
    return Account.create(owner_id="owner-1", currency="KRW", email="owner1@example.com")


def _three_months_history(account_id: str) -> list[SpendingAnalysis]:
    amounts = [10000, 20000, 30000]
    months = ["2026-04", "2026-05", "2026-06"]
    return [
        SpendingAnalysis.create(
            account_id=account_id,
            analysis_month=months[i],
            total_amount=amounts[i],
            transaction_count=1,
            previous_total_amount=amounts[i - 1] if i > 0 else 0,
        )
        for i in range(3)
    ]


@pytest.mark.asyncio
async def test_execute_when_an_account_has_3_months_of_history_and_no_forecast_yet_then_trains_and_saves(
    repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model
) -> None:
    account = _active_account()
    history = _three_months_history(account.account_id)
    repo.find_accounts.side_effect = [([account], 1), ([], 1)]
    spending_analysis_repo.find_recent_analyses.return_value = history
    spending_forecast_model.predict.return_value = SpendingForecastPrediction(predicted_amount=40000, confidence="HIGH")
    handler = ForecastSpendingHandler(repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model)

    forecasted_count = await handler.execute(FORECAST_MONTH)

    spending_analysis_repo.find_recent_analyses.assert_awaited_once_with(account.account_id, FORECAST_MONTH, 6)
    saved_forecast = spending_forecast_repo.save_forecast.await_args.args[0]
    assert saved_forecast.account_id == account.account_id
    assert saved_forecast.forecast_month == FORECAST_MONTH
    assert saved_forecast.predicted_amount == 40000
    assert saved_forecast.confidence == "HIGH"
    assert saved_forecast.history_months_used == 3
    assert forecasted_count == 1


@pytest.mark.asyncio
async def test_execute_when_an_account_has_fewer_than_3_months_of_history_then_skips_it_without_training(
    repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model
) -> None:
    account = _active_account()
    repo.find_accounts.side_effect = [([account], 1), ([], 1)]
    spending_analysis_repo.find_recent_analyses.return_value = _three_months_history(account.account_id)[:2]
    handler = ForecastSpendingHandler(repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model)

    forecasted_count = await handler.execute(FORECAST_MONTH)

    spending_forecast_model.predict.assert_not_called()
    spending_forecast_repo.save_forecast.assert_not_called()
    assert forecasted_count == 0


@pytest.mark.asyncio
async def test_execute_when_an_account_already_has_a_forecast_for_the_month_then_skips_it(
    repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model
) -> None:
    account = _active_account()
    repo.find_accounts.side_effect = [([account], 1), ([], 1)]
    spending_forecast_repo.has_forecast.return_value = True
    handler = ForecastSpendingHandler(repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model)

    forecasted_count = await handler.execute(FORECAST_MONTH)

    spending_analysis_repo.find_recent_analyses.assert_not_called()
    spending_forecast_repo.save_forecast.assert_not_called()
    assert forecasted_count == 0


@pytest.mark.asyncio
async def test_execute_saves_nothing_when_there_are_no_active_accounts(
    repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model
) -> None:
    repo.find_accounts.return_value = ([], 0)
    handler = ForecastSpendingHandler(repo, spending_analysis_repo, spending_forecast_repo, spending_forecast_model)

    forecasted_count = await handler.execute(FORECAST_MONTH)

    assert forecasted_count == 0
    spending_forecast_repo.save_forecast.assert_not_called()
