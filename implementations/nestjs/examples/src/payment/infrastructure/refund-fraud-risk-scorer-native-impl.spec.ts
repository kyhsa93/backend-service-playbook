import { RefundFraudRiskScorerNativeImpl } from '@/payment/infrastructure/refund-fraud-risk-scorer-native-impl'

// The model trains once at construction against a fixed synthetic dataset (see the file under
// test), so this spec doesn't assert exact score values — it asserts the trained model orders
// an obviously risky pattern above an obviously safe one, and always returns a valid 0-1 score.
describe('RefundFraudRiskScorerNativeImpl', () => {
  const scorer = new RefundFraudRiskScorerNativeImpl()

  it('score_when_the_pattern_is_frequent_high_ratio_and_fast_after_payment_then_scores_higher_than_a_safe_pattern', async () => {
    const riskyScore = await scorer.score({
      refundCountLast30Days: 6,
      rejectedRefundCountLast30Days: 3,
      refundToPaymentAmountRatio: 1,
      minutesSincePayment: 5
    })
    const safeScore = await scorer.score({
      refundCountLast30Days: 0,
      rejectedRefundCountLast30Days: 0,
      refundToPaymentAmountRatio: 0.2,
      minutesSincePayment: 40000
    })

    expect(riskyScore).toBeGreaterThan(safeScore)
  })

  it('score_always_returns_a_value_between_0_and_1', async () => {
    const score = await scorer.score({
      refundCountLast30Days: 4,
      rejectedRefundCountLast30Days: 2,
      refundToPaymentAmountRatio: 0.7,
      minutesSincePayment: 100
    })

    expect(score).toBeGreaterThanOrEqual(0)
    expect(score).toBeLessThanOrEqual(1)
  })
})
