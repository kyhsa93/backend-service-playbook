import pytest

from src.payment.domain.refund_risk_features import RefundRiskFeatures
from src.payment.infrastructure.refund_fraud_risk_scorer_native_impl import RefundFraudRiskScorerNativeImpl

# The model trains once at module import time against a fixed synthetic dataset (see the file
# under test), so this spec doesn't assert exact score values — it asserts the trained model
# orders an obviously risky pattern above an obviously safe one, and always returns a valid
# 0-1 score.


@pytest.mark.asyncio
async def test_score_a_frequent_high_ratio_fast_after_payment_pattern_scores_higher_than_a_safe_pattern() -> None:
    scorer = RefundFraudRiskScorerNativeImpl()

    risky_score = await scorer.score(
        RefundRiskFeatures(
            refund_count_last_30_days=6,
            rejected_refund_count_last_30_days=3,
            refund_to_payment_amount_ratio=1.0,
            minutes_since_payment=5,
        )
    )
    safe_score = await scorer.score(
        RefundRiskFeatures(
            refund_count_last_30_days=0,
            rejected_refund_count_last_30_days=0,
            refund_to_payment_amount_ratio=0.2,
            minutes_since_payment=40000,
        )
    )

    assert risky_score > safe_score


@pytest.mark.asyncio
async def test_score_always_returns_a_value_between_0_and_1() -> None:
    scorer = RefundFraudRiskScorerNativeImpl()

    score = await scorer.score(
        RefundRiskFeatures(
            refund_count_last_30_days=4,
            rejected_refund_count_last_30_days=2,
            refund_to_payment_amount_ratio=0.7,
            minutes_since_payment=100,
        )
    )

    assert 0 <= score <= 1
