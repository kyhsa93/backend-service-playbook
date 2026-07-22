package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import org.springframework.stereotype.Service

// Violation — the Query Service returns the raw Account Aggregate as-is instead of a dedicated Result data class.
@Service
class GetAccountService(
    private val accountQuery: AccountQuery,
) {
    fun getAccount(accountId: String): Account = accountQuery.findAccounts().first()
}
