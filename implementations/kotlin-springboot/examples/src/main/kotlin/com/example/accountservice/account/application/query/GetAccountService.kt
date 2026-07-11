package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) {

    fun getAccount(accountId: String, requesterId: String): GetAccountResult {
        val account = accountQuery.findByAccountIdAndOwnerId(accountId, requesterId)
            ?: throw AccountNotFoundException(accountId)
        return GetAccountResult(
            accountId = account.accountId,
            ownerId = account.ownerId,
            email = account.email,
            balance = GetAccountResult.MoneyResult(account.balance.amount, account.balance.currency),
            status = account.status.name,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt,
        )
    }
}
