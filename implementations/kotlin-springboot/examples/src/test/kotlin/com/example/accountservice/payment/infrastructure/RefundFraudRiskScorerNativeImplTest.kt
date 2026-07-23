package com.example.accountservice.payment.infrastructure

import com.example.accountservice.payment.domain.RefundRiskFeatures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The model trains once at construction against a fixed synthetic dataset (see the class under
 * test), so this test doesn't assert exact score values — it asserts the trained model orders an
 * obviously risky pattern above an obviously safe one, and always returns a valid 0-1 score.
 */
class RefundFraudRiskScorerNativeImplTest {
    private val scorer = RefundFraudRiskScorerNativeImpl()

    @Test
    fun `scores a frequent, high-ratio, fast-after-payment pattern higher than a safe pattern`() {
        val riskyScore =
            scorer.score(
                RefundRiskFeatures(
                    refundCountLast30Days = 6,
                    rejectedRefundCountLast30Days = 3,
                    refundToPaymentAmountRatio = 1.0,
                    minutesSincePayment = 5.0,
                ),
            )
        val safeScore =
            scorer.score(
                RefundRiskFeatures(
                    refundCountLast30Days = 0,
                    rejectedRefundCountLast30Days = 0,
                    refundToPaymentAmountRatio = 0.2,
                    minutesSincePayment = 40000.0,
                ),
            )

        assertThat(riskyScore).isGreaterThan(safeScore)
    }

    @Test
    fun `always returns a value between 0 and 1`() {
        val score =
            scorer.score(
                RefundRiskFeatures(
                    refundCountLast30Days = 4,
                    rejectedRefundCountLast30Days = 2,
                    refundToPaymentAmountRatio = 0.7,
                    minutesSincePayment = 100.0,
                ),
            )

        assertThat(score).isGreaterThanOrEqualTo(0.0)
        assertThat(score).isLessThanOrEqualTo(1.0)
    }
}
