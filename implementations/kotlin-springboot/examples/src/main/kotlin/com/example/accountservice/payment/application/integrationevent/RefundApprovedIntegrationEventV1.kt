package com.example.accountservice.payment.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * The Integration Event (public contract) that Payment BC exposes to an external BC (Account).
 * Carries only the minimal information Account needs to execute the refund credit (deposit).
 */
data class RefundApprovedIntegrationEventV1(
    val refundId: String,
    val paymentId: String,
    val accountId: String,
    val amount: Long,
    val approvedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "refund.approved.v1"
    }
}
