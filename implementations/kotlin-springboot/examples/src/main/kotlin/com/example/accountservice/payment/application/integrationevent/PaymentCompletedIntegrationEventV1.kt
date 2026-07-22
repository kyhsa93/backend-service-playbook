package com.example.accountservice.payment.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * The Integration Event (public contract) that Payment BC exposes to an external BC (Account).
 * A thin contract carrying only the minimal information (accountId+amount) Account needs for the
 * actual deduction (withdraw) — it does not expose Payment's internal model such as ownerId/cardId.
 * paymentId is also carried as the correlation key Account BC uses for idempotency judgment
 * (Level 2 Ledger: duplicate check on the referenceId+type combination).
 */
data class PaymentCompletedIntegrationEventV1(
    val paymentId: String,
    val accountId: String,
    val amount: Long,
    val completedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "payment.completed.v1"
    }
}
