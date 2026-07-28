from src.account.domain.spending_forecast import SpendingForecast


def test_create_builds_a_forecast_row_with_a_generated_id_and_the_given_fields() -> None:
    forecast = SpendingForecast.create(
        account_id="account-1",
        forecast_month="2026-07",
        predicted_amount=40000,
        confidence="HIGH",
        history_months_used=3,
    )

    assert forecast.forecast_id
    assert forecast.account_id == "account-1"
    assert forecast.forecast_month == "2026-07"
    assert forecast.predicted_amount == 40000
    assert forecast.confidence == "HIGH"
    assert forecast.history_months_used == 3
    assert forecast.created_at is not None


def test_create_generates_a_different_id_on_each_call() -> None:
    first = SpendingForecast.create(
        account_id="account-1", forecast_month="2026-07", predicted_amount=0, confidence="LOW", history_months_used=3
    )
    second = SpendingForecast.create(
        account_id="account-1", forecast_month="2026-07", predicted_amount=0, confidence="LOW", history_months_used=3
    )

    assert first.forecast_id != second.forecast_id
