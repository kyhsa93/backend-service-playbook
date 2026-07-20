package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.Account
import org.springframework.stereotype.Service

// 위반 — Query Service가 전용 Result data class 대신 raw Account Aggregate를 그대로 반환한다.
@Service
class GetAccountService(
    private val accountQuery: AccountQuery,
) {
    fun getAccount(accountId: String): Account = accountQuery.findAccounts().first()
}
