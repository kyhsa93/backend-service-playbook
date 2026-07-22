package com.example.accountservice.payment.infrastructure

import com.example.accountservice.account.application.query.AccountQuery
import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountStatus
import com.example.accountservice.payment.application.adapter.AccountAdapter
import com.example.accountservice.payment.application.adapter.AccountView
import org.springframework.stereotype.Component

/**
 * The implementation of [AccountAdapter] (ACL). Injects and calls the read port ([AccountQuery])
 * exposed by Account BC, and translates Account BC's model into the minimal shape Payment BC uses
 * ([AccountView]) — including the balance (balanceAmount), moving it into the shape needed to judge
 * "whether payment is possible."
 *
 * Why the class name carries a `Payment` prefix: Card BC already has
 * [com.example.accountservice.card.infrastructure.AccountAdapterImpl] playing the same role, and
 * Spring's default bean-name generation looks only at the simple class name (even across different
 * packages), so two `AccountAdapterImpl` classes trigger a `ConflictingBeanDefinitionException` — this
 * naming convention exists specifically to avoid that collision.
 */
@Component
class PaymentAccountAdapterImpl(
    private val accountQuery: AccountQuery,
) : AccountAdapter {
    override fun findAccount(
        accountId: String,
        ownerId: String,
    ): AccountView? {
        val (accounts, _) =
            accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = ownerId))
        return accounts.firstOrNull()?.let { account ->
            AccountView(
                accountId = account.accountId,
                active = account.status == AccountStatus.ACTIVE,
                balanceAmount = account.balance.amount,
                currency = account.balance.currency,
            )
        }
    }
}
