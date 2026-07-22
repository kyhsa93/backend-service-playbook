package com.example.accountservice.card.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.card.application.adapter.AccountAdapter
import com.example.accountservice.card.application.adapter.AccountView
import org.springframework.stereotype.Component

/**
 * Implementation of [AccountAdapter] (ACL). Injects and calls the read port ([AccountQuery]) exposed
 * by the Account BC, and translates the Account BC's model
 * ([com.example.accountservice.account.domain.Account]/[AccountStatus]) into the minimal form the Card
 * BC uses ([AccountView]). It does not reference Account's write Repository or domain methods.
 *
 * Account's "account not found" signal is `AccountQuery.findAccounts` returning an empty list (single
 * lookups are also handled via `take = 1` + `firstOrNull()` — repository-pattern.md), and this is
 * propagated as-is to `null`, which the Card domain understands — Account's exception types (such as
 * AccountNotFoundException) never leak into the Card layer.
 */
@Component
class AccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {
    override fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView? {
        val (accounts, _) =
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = ownerId))
        return accounts.firstOrNull()?.let { account ->
            AccountView(accountId = account.accountId, active = account.status == AccountStatus.ACTIVE, email = account.email)
        }
    }
}
