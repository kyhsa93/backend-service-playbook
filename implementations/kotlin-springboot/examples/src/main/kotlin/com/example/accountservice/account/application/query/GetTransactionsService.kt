package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.AccountFindQuery
import com.example.accountservice.account.domain.AccountNotFoundException
import com.example.accountservice.account.domain.TransactionFindQuery
import com.example.accountservice.account.domain.TransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
@Transactional(readOnly = true)
class GetTransactionsService(
    private val accountQuery: AccountQuery,
) {
    fun getTransactions(
        accountId: String,
        requesterId: String,
        page: Int,
        take: Int,
        type: TransactionType? = null,
        fromDate: LocalDate? = null,
        toDate: LocalDate? = null,
    ): GetTransactionsResult {
        val (accounts, _) =
            accountQuery.findAccounts(
                AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId),
            )
        accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)

        val (transactions, count) =
            accountQuery.findTransactions(
                TransactionFindQuery(accountId = accountId, page = page, take = take, type = type, fromDate = fromDate, toDate = toDate),
            )

        return GetTransactionsResult(
            transactions =
                transactions.map {
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
