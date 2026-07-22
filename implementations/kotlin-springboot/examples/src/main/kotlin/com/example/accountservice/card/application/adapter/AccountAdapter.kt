package com.example.accountservice.card.application.adapter

/**
 * Adapter interface (Anticorruption Layer) for synchronously querying the Account BC.
 *
 * The synchronous Adapter pattern is used because, when issuing a card, whether the linked account
 * exists and is active must be confirmed immediately within the current request (see
 * cross-domain.md). The return type does not expose the Account BC's `AccountStatus`/`Account`; it is
 * translated into the minimal form the Card BC needs ([AccountView]) — the purpose of the ACL is to
 * keep upstream (Account) model changes from leaking into the Card domain. The actual translation is
 * handled by [com.example.accountservice.card.infrastructure.AccountAdapterImpl].
 */
interface AccountAdapter {
    fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView?
}

// email was added to obtain the recipient for the monthly card-usage-statement
// (SendMonthlyCardStatementsService) — since Card itself has no email (it's account-owner
// information, not card information), it is synchronously queried from the Account BC each time.
data class AccountView(
    val accountId: String,
    val active: Boolean,
    val email: String,
)
