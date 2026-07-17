package com.example.accountservice.account.application.command

import java.time.LocalDateTime

data class TransactionResult(
    val transactionId: String,
    val accountId: String,
    val type: String,
    val amount: MoneyResult,
    val createdAt: LocalDateTime,
) {
    data class MoneyResult(
        val amount: Long,
        val currency: String,
    )
}
