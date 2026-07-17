package com.example.accountservice.account.application.command

import java.time.LocalDateTime

data class CreateAccountResult(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val balance: MoneyResult,
    val status: String,
    val createdAt: LocalDateTime,
) {
    data class MoneyResult(
        val amount: Long,
        val currency: String,
    )
}
