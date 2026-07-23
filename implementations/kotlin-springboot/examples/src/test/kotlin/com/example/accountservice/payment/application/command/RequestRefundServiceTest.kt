package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.application.service.RefundFraudRiskScorer
import com.example.accountservice.payment.application.service.RefundReasonClassifier
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.RefundReasonCategory
import com.example.accountservice.payment.domain.RefundReasonClassification
import com.example.accountservice.payment.domain.RefundRepository
import com.example.accountservice.payment.domain.RefundStatus
import com.example.accountservice.payment.domain.RefundSummary
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * RequestRefundService loads two Aggregates (Payment/Refund), classifies the reason via the (mocked)
 * RefundReasonClassifier Technical Service, scores the refund's history pattern via the (mocked)
 * RefundFraudRiskScorer Technical Service, and delegates the decision to RefundEligibilityService (a
 * Domain Service) — this test verifies, from the Application layer's perspective, that the coordinated
 * outcome correctly leads to Refund.approve()/reject() (the unit test for the decision logic itself is
 * handled by RefundEligibilityServiceTest). Mocking both Technical Service interfaces — rather than
 * hitting a real LLM/ML model — is exactly the benefit described in domain-service.md: no external
 * dependency, no non-determinism, in this test.
 */
class RequestRefundServiceTest {
    private val paymentRepository = mockk<PaymentRepository>(relaxed = true)
    private val refundRepository = mockk<RefundRepository>(relaxed = true)
    private val refundReasonClassifier = mockk<RefundReasonClassifier>()
    private val refundFraudRiskScorer = mockk<RefundFraudRiskScorer>()
    private val service =
        RequestRefundService(paymentRepository, refundRepository, refundReasonClassifier, refundFraudRiskScorer)

    init {
        every { refundReasonClassifier.classify(any()) } returns
            RefundReasonClassification(category = RefundReasonCategory.DEFECTIVE_PRODUCT, fraudRiskScore = 0.1)
        every { refundRepository.summarizeRefundsByOwner(any()) } returns RefundSummary(count = 0)
        every { refundFraudRiskScorer.score(any()) } returns 0.1
    }

    private fun stubPayment(payment: Payment) {
        every {
            paymentRepository.findPayments(PaymentFindQuery(page = 0, take = 1, paymentId = payment.paymentId, ownerId = "owner-1"))
        } returns (listOf(payment) to 1L)
    }

    @Test
    fun `requesting a refund at or under the amount for a completed payment is approved and saved`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(
                    paymentId = payment.paymentId,
                    amount = 500,
                    reason = "Simple change of mind",
                    requesterId = "owner-1",
                ),
            )

        assertThat(result.status).isEqualTo(RefundStatus.APPROVED.name)
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
        verify(exactly = 1) { refundReasonClassifier.classify("Simple change of mind") }
    }

    @Test
    fun `requesting a refund for a payment that is not completed is rejected and saved, but does not throw an exception`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(
                    paymentId = payment.paymentId,
                    amount = 500,
                    reason = "Simple change of mind",
                    requesterId = "owner-1",
                ),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote).isEqualTo("A refund can only be requested for a completed payment.")
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
    }

    @Test
    fun `a refund amount exceeding the payment amount is rejected and saved`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(
                    paymentId = payment.paymentId,
                    amount = 1500,
                    reason = "Simple change of mind",
                    requesterId = "owner-1",
                ),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote).isEqualTo("The refund amount cannot exceed the payment amount.")
    }

    @Test
    fun `a refund reason flagged as high fraud risk by the classifier is rejected and saved`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)
        every { refundReasonClassifier.classify("suspicious reason") } returns
            RefundReasonClassification(category = RefundReasonCategory.FRAUD_SUSPECTED, fraudRiskScore = 0.95)

        val result =
            service.requestRefund(
                RequestRefundCommand(
                    paymentId = payment.paymentId,
                    amount = 500,
                    reason = "suspicious reason",
                    requesterId = "owner-1",
                ),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote).isEqualTo("This refund reason was flagged as high fraud risk and requires manual review.")
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
    }

    @Test
    fun `a refund flagged as high risk by the ML fraud-risk scorer is rejected and saved, even with a low-risk LLM classification`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)
        every { refundFraudRiskScorer.score(any()) } returns 0.8

        val result =
            service.requestRefund(
                RequestRefundCommand(
                    paymentId = payment.paymentId,
                    amount = 500,
                    reason = "Simple change of mind",
                    requesterId = "owner-1",
                ),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote)
            .isEqualTo("This refund pattern was flagged as high risk by the fraud-risk model and requires manual review.")
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
    }

    @Test
    fun `assembles the risk features from the refund history summary and the payment-refund pair before scoring`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)
        every {
            refundRepository.summarizeRefundsByOwner(match { it.ownerId == "owner-1" && it.status == null })
        } returns RefundSummary(count = 4)
        every {
            refundRepository.summarizeRefundsByOwner(match { it.status != null })
        } returns RefundSummary(count = 2)

        service.requestRefund(
            RequestRefundCommand(
                paymentId = payment.paymentId,
                amount = 500,
                reason = "Simple change of mind",
                requesterId = "owner-1",
            ),
        )

        verify(exactly = 1) {
            refundFraudRiskScorer.score(
                match {
                    it.refundCountLast30Days == 4 &&
                        it.rejectedRefundCountLast30Days == 2 &&
                        it.refundToPaymentAmountRatio == 0.5
                },
            )
        }
    }

    @Test
    fun `throws an exception when the payment cannot be found`() {
        every {
            paymentRepository.findPayments(PaymentFindQuery(page = 0, take = 1, paymentId = "non-existent", ownerId = "owner-1"))
        } returns (emptyList<Payment>() to 0L)

        assertThrows<PaymentNotFoundException> {
            service.requestRefund(
                RequestRefundCommand(paymentId = "non-existent", amount = 500, reason = "Simple change of mind", requesterId = "owner-1"),
            )
        }
        verify(exactly = 0) { refundRepository.saveRefund(any()) }
    }
}
