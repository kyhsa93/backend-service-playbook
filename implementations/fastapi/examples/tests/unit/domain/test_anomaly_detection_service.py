from src.account.domain.anomaly_detection_service import AnomalyDetectionService


def test_is_anomalous_when_history_has_fewer_than_5_withdrawals_then_returns_false_regardless_of_amount() -> None:
    service = AnomalyDetectionService()

    result = service.is_anomalous([10000, 10000, 10000, 10000], 5000000)

    assert result is False


def test_is_anomalous_when_the_amount_is_close_to_the_historical_mean_then_returns_false() -> None:
    service = AnomalyDetectionService()
    history = [10000, 12000, 9000, 11000, 10500, 9500]

    result = service.is_anomalous(history, 10800)

    assert result is False


def test_is_anomalous_when_the_amount_is_far_beyond_the_historical_spread_then_returns_true() -> None:
    service = AnomalyDetectionService()
    history = [10000, 12000, 9000, 11000, 10500, 9500]

    result = service.is_anomalous(history, 5000000)

    assert result is True


def test_is_anomalous_when_history_is_perfectly_uniform_and_the_amount_matches_it_then_returns_false() -> None:
    service = AnomalyDetectionService()

    result = service.is_anomalous([10000, 10000, 10000, 10000, 10000], 10000)

    assert result is False


def test_is_anomalous_when_history_is_perfectly_uniform_and_the_amount_differs_then_returns_true() -> None:
    service = AnomalyDetectionService()

    result = service.is_anomalous([10000, 10000, 10000, 10000, 10000], 10001)

    assert result is True
