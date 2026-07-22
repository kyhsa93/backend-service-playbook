package com.example.accountservice.account.application.integrationevent

import com.example.accountservice.outbox.IntegrationEventContract

/**
 * The Integration Event (public contract) that the Account BC exposes to external BCs (Card, etc.).
 *
 * Kept separate from the internal Domain Event
 * ([com.example.accountservice.account.domain.AccountSuspendedEvent]) to keep the name/schema stable and
 * explicitly versioned. The [eventName] literal is used as the Outbox row's eventType, becoming the
 * routing key for [com.example.accountservice.outbox.EventHandlerRegistry].
 */
data class AccountSuspendedIntegrationEventV1(
    val accountId: String,
    val suspendedAt: String,
) : IntegrationEventContract {
    override val eventName: String get() = EVENT_NAME

    companion object {
        const val EVENT_NAME = "account.suspended.v1"
    }
}
