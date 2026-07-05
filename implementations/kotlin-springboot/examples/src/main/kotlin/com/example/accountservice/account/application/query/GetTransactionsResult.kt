package com.example.accountservice.account.application.query

import java.time.LocalDateTime

data class GetTransactionsResult(val transactions: List<TransactionSummary>, val count: Long) {
    data class TransactionSummary(
        val transactionId: String,
        val type: String,
        val amount: MoneyResult,
        val createdAt: LocalDateTime,
    )

    data class MoneyResult(val amount: Long, val currency: String)
}
