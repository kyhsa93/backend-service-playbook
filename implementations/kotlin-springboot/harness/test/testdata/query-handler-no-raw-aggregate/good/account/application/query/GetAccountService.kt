package com.example.accountservice.account.application.query

import org.springframework.stereotype.Service

data class GetAccountResult(val accountId: String)

@Service
class GetAccountService(
    private val accountQuery: AccountQuery,
) {
    fun getAccount(accountId: String): GetAccountResult {
        val account = accountQuery.findAccounts().first()
        return GetAccountResult(account.accountId)
    }
}
