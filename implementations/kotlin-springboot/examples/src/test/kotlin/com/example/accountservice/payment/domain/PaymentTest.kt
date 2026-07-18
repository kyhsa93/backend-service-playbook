package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaymentTest {
    private fun createPayment(): Payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)

    @Test
    fun `create 하면 PENDING 상태의 결제가 생성된다`() {
        val payment = createPayment()

        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.cardId).isEqualTo("card-1")
        assertThat(payment.accountId).isEqualTo("account-1")
        assertThat(payment.ownerId).isEqualTo("owner-1")
        assertThat(payment.amount).isEqualTo(1000)
        assertThat(payment.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `결제 ID는 하이픈 없는 32자리 hex 문자열이다`() {
        val payment = createPayment()

        assertThat(payment.paymentId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `complete 하면 COMPLETED 상태가 되고 PaymentCompletedEvent가 수집된다`() {
        val payment = createPayment()

        payment.complete()

        assertThat(payment.status).isEqualTo(PaymentStatus.COMPLETED)
        val events = payment.pullDomainEvents()
        assertThat(events).hasSize(1)
        val event = events.first() as PaymentCompletedEvent
        assertThat(event.paymentId).isEqualTo(payment.paymentId)
        assertThat(event.accountId).isEqualTo("account-1")
        assertThat(event.amount).isEqualTo(1000)
    }

    @Test
    fun `PENDING이 아닌 결제를 complete 하면 예외를 던진다`() {
        val payment = createPayment()
        payment.complete()

        assertThrows<PaymentCompleteRequiresPendingPaymentException> { payment.complete() }
    }

    @Test
    fun `fail 하면 FAILED 상태가 된다`() {
        val payment = createPayment()

        payment.fail("게이트웨이 오류")

        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `PENDING이 아닌 결제를 fail 하면 예외를 던진다`() {
        val payment = createPayment()
        payment.complete()

        assertThrows<PaymentFailRequiresPendingPaymentException> { payment.fail("게이트웨이 오류") }
    }

    @Test
    fun `COMPLETED 결제를 cancel 하면 CANCELLED 상태가 되고 PaymentCancelledEvent가 수집된다`() {
        val payment = createPayment()
        payment.complete()
        payment.pullDomainEvents()

        payment.cancel("고객 요청")

        assertThat(payment.status).isEqualTo(PaymentStatus.CANCELLED)
        val events = payment.pullDomainEvents()
        assertThat(events).hasSize(1)
        val event = events.first() as PaymentCancelledEvent
        assertThat(event.reason).isEqualTo("고객 요청")
        assertThat(event.amount).isEqualTo(1000)
    }

    @Test
    fun `PENDING 결제를 cancel 하면 예외를 던진다`() {
        val payment = createPayment()

        assertThrows<PaymentCancelRequiresCompletedPaymentException> { payment.cancel("고객 요청") }
    }

    @Test
    fun `이미 취소된 결제를 다시 cancel 하면 예외를 던진다`() {
        val payment = createPayment()
        payment.complete()
        payment.cancel("고객 요청")

        assertThrows<PaymentCancelRequiresCompletedPaymentException> { payment.cancel("고객 요청") }
    }
}
