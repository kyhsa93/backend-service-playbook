package com.example.accountservice.payment.application.command

import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import com.example.accountservice.payment.domain.RefundRepository
import com.example.accountservice.payment.domain.RefundStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * RequestRefundService loads two Aggregates (Payment/Refund) and delegates the decision to
 * RefundEligibilityService (a Domain Service) — this test verifies, from the Application layer's
 * perspective, that the coordinated outcome correctly leads to Refund.approve()/reject() (the unit
 * test for the decision logic itself is handled by RefundEligibilityServiceTest).
 */
class RequestRefundServiceTest {
    private val paymentRepository = mockk<PaymentRepository>(relaxed = true)
    private val refundRepository = mockk<RefundRepository>(relaxed = true)
    private val service = RequestRefundService(paymentRepository, refundRepository)

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
