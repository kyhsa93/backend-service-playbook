package com.example.accountservice.account.application.query

import java.time.LocalDateTime

data class GetAccountResult(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val balance: MoneyResult,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    data class MoneyResult(
        val amount: Long,
        val currency: String,
    )
}
