package com.example.accountservice.payment.application.adapter

/**
 * An Adapter interface for synchronously querying Account BC (Anticorruption Layer).
 *
 * Uses the synchronous Adapter pattern because whether payment is possible (whether the account is
 * active + whether the balance is sufficient) must be verified immediately within the current
 * request. The actual deduction is not this synchronous query's responsibility — Account BC
 * subscribes to the `payment.completed.v1` Integration Event and performs it asynchronously (the
 * "synchronous = query, asynchronous Integration Event = state change" principle from
 * cross-domain.md). It shares its name with Card BC's
 * [com.example.accountservice.card.application.adapter.AccountAdapter] but is a distinct type because
 * the package differs — Payment also needs the balance (balanceAmount), so [AccountView]'s shape
 * differs.
 */
interface AccountAdapter {
    fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView?
}

data class AccountView(
    val accountId: String,
    val active: Boolean,
    val balanceAmount: Long,
    val currency: String,
)
