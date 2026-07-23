package ml_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/domain/payment"
	"github.com/example/account-service/internal/infrastructure/ml"
)

// The model trains once at construction against a fixed synthetic dataset
// (see the file under test), so this test doesn't assert exact score values
// — it asserts the trained model orders an obviously risky pattern above an
// obviously safe one, and always returns a valid 0-1 score.
func TestRefundFraudRiskScorerNativeImpl_Score(t *testing.T) {
	scorer := ml.NewRefundFraudRiskScorerNativeImpl()

	t.Run("score_when_the_pattern_is_frequent_high_ratio_and_fast_after_payment_then_scores_higher_than_a_safe_pattern", func(t *testing.T) {
		riskyScore := scorer.Score(context.Background(), payment.RefundRiskFeatures{
			RefundCountLast30Days:         6,
			RejectedRefundCountLast30Days: 3,
			RefundToPaymentAmountRatio:    1,
			MinutesSincePayment:           5,
		})
		safeScore := scorer.Score(context.Background(), payment.RefundRiskFeatures{
			RefundCountLast30Days:         0,
			RejectedRefundCountLast30Days: 0,
			RefundToPaymentAmountRatio:    0.2,
			MinutesSincePayment:           40000,
		})

		if riskyScore <= safeScore {
			t.Fatalf("riskyScore = %v, want greater than safeScore = %v", riskyScore, safeScore)
		}
	})

	t.Run("score_always_returns_a_value_between_0_and_1", func(t *testing.T) {
		score := scorer.Score(context.Background(), payment.RefundRiskFeatures{
			RefundCountLast30Days:         4,
			RejectedRefundCountLast30Days: 2,
			RefundToPaymentAmountRatio:    0.7,
			MinutesSincePayment:           100,
		})

		if score < 0 || score > 1 {
			t.Fatalf("score = %v, want in [0, 1]", score)
		}
	})
}
