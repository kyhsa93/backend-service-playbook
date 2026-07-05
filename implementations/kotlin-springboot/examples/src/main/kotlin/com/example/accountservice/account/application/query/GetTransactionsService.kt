package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.AccountRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetTransactionsService(private val accountRepository: AccountRepository) {

    fun getTransactions(accountId: String, requesterId: String, page: Int, take: Int): GetTransactionsResult {
        accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
            ?: throw AccountNotFoundException(accountId)

        val transactions = accountRepository.findTransactions(accountId, page, take)
        val count = accountRepository.countTransactions(accountId)

        return GetTransactionsResult(
            transactions = transactions.map {
                GetTransactionsResult.TransactionSummary(
                    transactionId = it.transactionId,
                    type = it.type.name,
                    amount = GetTransactionsResult.MoneyResult(it.amount.amount, it.amount.currency),
                    createdAt = it.createdAt,
                )
            },
            count = count,
        )
    }
}
