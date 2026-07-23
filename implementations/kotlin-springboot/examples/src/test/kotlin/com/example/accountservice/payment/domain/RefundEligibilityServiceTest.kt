package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for RefundEligibilityService (a Domain Service) — this class is instantiated directly
 * as `RefundEligibilityService()` without going through the Application layer, to verify only the
 * decision logic (see domain-service.md's "a real working example"). No LLM call — the classification
 * is always passed in as a plain value.
 */
class RefundEligibilityServiceTest {
    private val service = RefundEligibilityService()

    private val notFraud = RefundReasonClassification(category = RefundReasonCategory.DEFECTIVE_PRODUCT, fraudRiskScore = 0.1)

    private fun completedPayment(amount: Long = 1000): Payment {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = amount)
        payment.complete()
        return payment
    }

    @Test
    fun `is approved when the original payment is COMPLETED and the refund amount is at or under the payment amount`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.0)

        assertThat(decision.approved).isTrue()
        assertThat(decision.reason).isNull()
    }

    @Test
    fun `is approved even when the refund amount equals the payment amount`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 1000, reason = "Full refund")

        val decision = service.evaluate(payment, refund, notFraud, 0.0)

        assertThat(decision.approved).isTrue()
    }

    @Test
    fun `is rejected when the original payment is not COMPLETED (PENDING)`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.0)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("A refund can only be requested for a completed payment.")
    }

    @Test
    fun `is rejected when the original payment is CANCELLED`() {
        val payment = completedPayment(1000)
        payment.cancel("Customer request")
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.0)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("A refund can only be requested for a completed payment.")
    }

    @Test
    fun `is rejected when the refund amount exceeds the payment amount`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 1001, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.0)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("The refund amount cannot exceed the payment amount.")
    }

    @Test
    fun `is rejected when the classification is FRAUD_SUSPECTED with a fraud risk score at or above the threshold`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "suspicious reason")

        val decision =
            service.evaluate(
                payment,
                refund,
                RefundReasonClassification(category = RefundReasonCategory.FRAUD_SUSPECTED, fraudRiskScore = 0.9),
                0.0,
            )

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason).isEqualTo("This refund reason was flagged as high fraud risk and requires manual review.")
    }

    @Test
    fun `is still approved when the classification is FRAUD_SUSPECTED but the score is below the threshold`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision =
            service.evaluate(
                payment,
                refund,
                RefundReasonClassification(category = RefundReasonCategory.FRAUD_SUSPECTED, fraudRiskScore = 0.5),
                0.0,
            )

        assertThat(decision.approved).isTrue()
    }

    @Test
    fun `is still approved when the score is high but the category is not FRAUD_SUSPECTED`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision =
            service.evaluate(
                payment,
                refund,
                RefundReasonClassification(category = RefundReasonCategory.OTHER, fraudRiskScore = 0.95),
                0.0,
            )

        assertThat(decision.approved).isTrue()
    }

    @Test
    fun `is rejected when the ML fraud-risk score is at or above its own threshold, independent of the LLM classification`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.8)

        assertThat(decision.approved).isFalse()
        assertThat(decision.reason)
            .isEqualTo("This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.")
    }

    @Test
    fun `is still approved when the ML fraud-risk score is below its own threshold`() {
        val payment = completedPayment(1000)
        val refund = Refund.create(paymentId = payment.paymentId, amount = 500, reason = "Simple change of mind")

        val decision = service.evaluate(payment, refund, notFraud, 0.79)

        assertThat(decision.approved).isTrue()
    }
}
