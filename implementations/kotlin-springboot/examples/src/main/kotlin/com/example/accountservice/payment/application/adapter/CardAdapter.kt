package com.example.accountservice.payment.application.adapter

/**
 * An Adapter interface for synchronously querying Card BC (Anticorruption Layer).
 *
 * Uses the synchronous Adapter pattern (see cross-domain.md) because whether the card exists and is
 * active, and what accountId it's linked to, must be verified immediately within the current request
 * at payment time. Payment reuses the same pattern
 * ([com.example.accountservice.card.application.adapter.AccountAdapter]) that Card BC already uses to
 * query Account — the return type does not expose Card BC's `CardStatus` and instead translates it
 * into the minimal shape Payment BC needs ([CardView]). The actual translation is handled by
 * [com.example.accountservice.payment.infrastructure.PaymentCardAdapterImpl].
 */
interface CardAdapter {
    fun findCard(
        cardId: String,
        ownerId: String,
    ): CardView?
}

data class CardView(
    val cardId: String,
    val accountId: String,
    val active: Boolean,
)
