package com.example.accountservice.payment.application.command

import com.example.accountservice.outbox.OutboxRelay
import com.example.accountservice.payment.domain.Payment
import com.example.accountservice.payment.domain.PaymentFindQuery
import com.example.accountservice.payment.domain.PaymentNotFoundException
import com.example.accountservice.payment.domain.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CancelPaymentServiceTest {
    private val paymentRepository = mockk<PaymentRepository>(relaxed = true)
    private val outboxRelay = mockk<OutboxRelay>(relaxed = true)
    private val service = CancelPaymentService(paymentRepository, outboxRelay)

    private fun completedPayment(): Payment {
        val payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 500)
        payment.complete()
        payment.pullDomainEvents()
        return payment
    }

    @Test
    fun `완료된 결제를 취소하면 CANCELLED 상태로 저장되고 Outbox가 드레인된다`() {
        val payment = completedPayment()
        every {
            paymentRepository.findPayments(PaymentFindQuery(page = 0, take = 1, paymentId = payment.paymentId, ownerId = "owner-1"))
        } returns (listOf(payment) to 1L)

        service.cancel(CancelPaymentCommand(paymentId = payment.paymentId, reason = "고객 요청", requesterId = "owner-1"))

        assertThat(payment.status.name).isEqualTo("CANCELLED")
        verify(exactly = 1) { paymentRepository.savePayment(payment) }
        verify(exactly = 1) { outboxRelay.processPending() }
    }

    @Test
    fun `결제를 찾을 수 없으면 예외를 던진다`() {
        every {
            paymentRepository.findPayments(PaymentFindQuery(page = 0, take = 1, paymentId = "non-existent", ownerId = "owner-1"))
        } returns (emptyList<Payment>() to 0L)

        assertThrows<PaymentNotFoundException> {
            service.cancel(CancelPaymentCommand(paymentId = "non-existent", reason = "고객 요청", requesterId = "owner-1"))
        }
        verify(exactly = 0) { paymentRepository.savePayment(any()) }
    }
}
