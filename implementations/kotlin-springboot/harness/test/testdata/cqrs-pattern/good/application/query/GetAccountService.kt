package com.example.accountservice.account.application.query

import org.springframework.stereotype.Service

@Service
class GetAccountService(private val accountQuery: AccountQuery) {
    fun getAccount(accountId: String, requesterId: String): Any? =
        accountQuery.findByAccountIdAndOwnerId(accountId, requesterId)
}
