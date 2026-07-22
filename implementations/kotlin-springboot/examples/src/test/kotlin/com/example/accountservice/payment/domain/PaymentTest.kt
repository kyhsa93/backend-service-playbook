package com.example.accountservice.payment.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PaymentTest {
    private fun createPayment(): Payment = Payment.create(cardId = "card-1", accountId = "account-1", ownerId = "owner-1", amount = 1000)

    @Test
    fun `create produces a payment in the PENDING state`() {
        val payment = createPayment()

        assertThat(payment.status).isEqualTo(PaymentStatus.PENDING)
        assertThat(payment.cardId).isEqualTo("card-1")
        assertThat(payment.accountId).isEqualTo("account-1")
        assertThat(payment.ownerId).isEqualTo("owner-1")
        assertThat(payment.amount).isEqualTo(1000)
        assertThat(payment.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `the payment ID is a 32-character hex string without hyphens`() {
        val payment = createPayment()

        assertThat(payment.paymentId).matches("^[0-9a-f]{32}$")
    }

    @Test
    fun `complete transitions to COMPLETED and collects a PaymentCompletedEvent`() {
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
    fun `completing a payment that is not PENDING throws an exception`() {
        val payment = createPayment()
        payment.complete()

        assertThrows<PaymentCompleteRequiresPendingPaymentException> { payment.complete() }
    }

    @Test
    fun `fail transitions to the FAILED state`() {
        val payment = createPayment()

        payment.fail("Gateway error")

        assertThat(payment.status).isEqualTo(PaymentStatus.FAILED)
        assertThat(payment.pullDomainEvents()).isEmpty()
    }

    @Test
    fun `failing a payment that is not PENDING throws an exception`() {
        val payment = createPayment()
        payment.complete()

        assertThrows<PaymentFailRequiresPendingPaymentException> { payment.fail("Gateway error") }
    }

    @Test
    fun `cancelling a COMPLETED payment transitions to CANCELLED and collects a PaymentCancelledEvent`() {
        val payment = createPayment()
        payment.complete()
        payment.pullDomainEvents()

        payment.cancel("Customer request")

        assertThat(payment.status).isEqualTo(PaymentStatus.CANCELLED)
        val events = payment.pullDomainEvents()
        assertThat(events).hasSize(1)
        val event = events.first() as PaymentCancelledEvent
        assertThat(event.reason).isEqualTo("Customer request")
        assertThat(event.amount).isEqualTo(1000)
    }

    @Test
    fun `cancelling a PENDING payment throws an exception`() {
        val payment = createPayment()

        assertThrows<PaymentCancelRequiresCompletedPaymentException> { payment.cancel("Customer request") }
    }

    @Test
    fun `cancelling an already-cancelled payment again throws an exception`() {
        val payment = createPayment()
        payment.complete()
        payment.cancel("Customer request")

        assertThrows<PaymentCancelRequiresCompletedPaymentException> { payment.cancel("Customer request") }
    }
}
