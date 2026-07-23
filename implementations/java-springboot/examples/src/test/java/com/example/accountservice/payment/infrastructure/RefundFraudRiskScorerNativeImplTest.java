package com.example.accountservice.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accountservice.payment.domain.RefundRiskFeatures;
import org.junit.jupiter.api.Test;

/**
 * RefundFraudRiskScorerNativeImpl trains a real (hand-rolled) logistic regression at construction
 * against a fixed synthetic dataset ({@code java.util.Random(42)}, so the outcome is fully
 * deterministic) — no mocking needed. This verifies the trained model actually separates a risky
 * refund-history pattern from a safe one, not just that the code runs without throwing.
 */
class RefundFraudRiskScorerNativeImplTest {

    private final RefundFraudRiskScorerNativeImpl scorer = new RefundFraudRiskScorerNativeImpl();

    @Test
    void scores_a_frequent_high_ratio_recent_refund_pattern_higher_than_a_safe_one() {
        // Frequent refunds, several rejected, nearly the full payment amount, requested minutes
        // after payment — every raw-generation-rule term in
        // model.py/RefundFraudRiskScorerNativeImpl
        // is pushed toward "risky."
        RefundRiskFeatures risky = new RefundRiskFeatures(7, 3, 0.95, 5);
        // The opposite: no refund history, a small fraction of the payment amount, requested
        // long after payment.
        RefundRiskFeatures safe = new RefundRiskFeatures(0, 0, 0.05, 40000);

        double riskyScore = scorer.score(risky);
        double safeScore = scorer.score(safe);

        assertThat(riskyScore).isGreaterThan(safeScore);
        assertThat(riskyScore).isGreaterThan(0.5);
        assertThat(safeScore).isLessThan(0.5);
    }

    @Test
    void always_returns_a_score_between_0_and_1_inclusive() {
        assertThat(scorer.score(new RefundRiskFeatures(0, 0, 0, 0))).isBetween(0.0, 1.0);
        assertThat(scorer.score(new RefundRiskFeatures(100, 100, 100, 100))).isBetween(0.0, 1.0);
    }

    @Test
    void is_deterministic_across_instances_since_the_training_seed_is_fixed() {
        RefundFraudRiskScorerNativeImpl anotherScorer = new RefundFraudRiskScorerNativeImpl();
        RefundRiskFeatures features = new RefundRiskFeatures(4, 2, 0.6, 200);

        assertThat(scorer.score(features)).isEqualTo(anotherScorer.score(features));
    }
}
