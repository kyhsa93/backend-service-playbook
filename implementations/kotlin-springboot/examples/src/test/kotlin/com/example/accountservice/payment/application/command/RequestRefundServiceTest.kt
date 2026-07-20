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
 * RequestRefundService는 두 Aggregate(Payment/Refund)를 로드해
 * RefundEligibilityService(Domain Service)에 판단을 위임한다 — 이 테스트는 그 조율 결과가
 * Refund.approve()/reject()로 올바르게 이어지는지 Application 레이어 관점에서 검증한다
 * (판단 로직 자체의 단위 테스트는 RefundEligibilityServiceTest가 전담).
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
    fun `완료된 결제에 대해 금액 이하로 환불을 요청하면 승인되고 저장된다`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(paymentId = payment.paymentId, amount = 500, reason = "단순 변심", requesterId = "owner-1"),
            )

        assertThat(result.status).isEqualTo(RefundStatus.APPROVED.name)
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
    }

    @Test
    fun `완료되지 않은 결제에 환불을 요청하면 거부되어 저장되지만 예외는 던지지 않는다`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(paymentId = payment.paymentId, amount = 500, reason = "단순 변심", requesterId = "owner-1"),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote).isEqualTo("완료된 결제에 대해서만 환불을 요청할 수 있습니다.")
        verify(exactly = 1) { refundRepository.saveRefund(any()) }
    }

    @Test
    fun `환불 금액이 결제 금액을 초과하면 거부되어 저장된다`() {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)
        payment.complete()
        stubPayment(payment)

        val result =
            service.requestRefund(
                RequestRefundCommand(paymentId = payment.paymentId, amount = 1500, reason = "단순 변심", requesterId = "owner-1"),
            )

        assertThat(result.status).isEqualTo(RefundStatus.REJECTED.name)
        assertThat(result.decisionNote).isEqualTo("환불 금액은 결제 금액을 초과할 수 없습니다.")
    }

    @Test
    fun `결제를 찾을 수 없으면 예외를 던진다`() {
        every {
            paymentRepository.findPayments(PaymentFindQuery(page = 0, take = 1, paymentId = "non-existent", ownerId = "owner-1"))
        } returns (emptyList<Payment>() to 0L)

        assertThrows<PaymentNotFoundException> {
            service.requestRefund(
                RequestRefundCommand(paymentId = "non-existent", amount = 500, reason = "단순 변심", requesterId = "owner-1"),
            )
        }
        verify(exactly = 0) { refundRepository.saveRefund(any()) }
    }
}
