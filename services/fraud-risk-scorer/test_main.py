from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_health_returns_ok():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_score_returns_a_value_between_0_and_1():
    response = client.post(
        "/score",
        json={
            "refundCountLast30Days": 4,
            "rejectedRefundCountLast30Days": 2,
            "refundToPaymentAmountRatio": 0.7,
            "minutesSincePayment": 100,
        },
    )

    assert response.status_code == 200
    risk_score = response.json()["riskScore"]
    assert 0 <= risk_score <= 1


def test_score_ranks_a_risky_pattern_above_a_safe_pattern():
    risky = client.post(
        "/score",
        json={
            "refundCountLast30Days": 6,
            "rejectedRefundCountLast30Days": 3,
            "refundToPaymentAmountRatio": 1,
            "minutesSincePayment": 5,
        },
    ).json()["riskScore"]
    safe = client.post(
        "/score",
        json={
            "refundCountLast30Days": 0,
            "rejectedRefundCountLast30Days": 0,
            "refundToPaymentAmountRatio": 0.2,
            "minutesSincePayment": 40000,
        },
    ).json()["riskScore"]

    assert risky > safe
