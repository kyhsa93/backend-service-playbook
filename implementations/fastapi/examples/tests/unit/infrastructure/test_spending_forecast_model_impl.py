from src.account.application.service.spending_forecast_model import SpendingHistoryPoint
from src.account.infrastructure.spending_forecast_model_impl import SpendingForecastModelImpl

model = SpendingForecastModelImpl()


def _history(*amounts: int) -> list[SpendingHistoryPoint]:
    months = ["2026-04", "2026-05", "2026-06", "2026-01", "2026-02", "2026-03"]
    return [SpendingHistoryPoint(analysis_month=months[i], total_amount=amount) for i, amount in enumerate(amounts)]


def test_predict_when_history_is_a_perfect_linear_trend_then_extrapolates_exactly_with_high_confidence() -> None:
    prediction = model.predict(_history(10000, 20000, 30000))

    assert prediction.predicted_amount == 40000
    assert prediction.confidence == "HIGH"


def test_predict_when_history_is_perfectly_flat_then_predicts_the_same_amount_with_high_confidence() -> None:
    prediction = model.predict(_history(15000, 15000, 15000))

    assert prediction.predicted_amount == 15000
    assert prediction.confidence == "HIGH"


def test_predict_when_history_is_noisy_and_non_linear_then_reports_lower_confidence() -> None:
    history = [
        SpendingHistoryPoint(analysis_month="2026-01", total_amount=5000),
        SpendingHistoryPoint(analysis_month="2026-02", total_amount=40000),
        SpendingHistoryPoint(analysis_month="2026-03", total_amount=3000),
        SpendingHistoryPoint(analysis_month="2026-04", total_amount=35000),
        SpendingHistoryPoint(analysis_month="2026-05", total_amount=4000),
        SpendingHistoryPoint(analysis_month="2026-06", total_amount=38000),
    ]

    prediction = model.predict(history)

    assert prediction.confidence != "HIGH"


def test_predict_when_the_trend_sharply_decreases_then_floors_the_prediction_at_0_instead_of_going_negative() -> None:
    prediction = model.predict(_history(30000, 15000, 1000))

    assert prediction.predicted_amount == 0
