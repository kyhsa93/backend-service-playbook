package com.example.accountservice.payment.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * The Integration Event (public contract) that Payment BC exposes to an external BC (Account).
 * Carries only the minimal information Account needs to execute the compensating credit (deposit).
 */
data class PaymentCancelledIntegrationEventV1(
    val paymentId: String,
    val accountId: String,
    val amount: Long,
    val cancelledAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "payment.cancelled.v1"
    }
}
