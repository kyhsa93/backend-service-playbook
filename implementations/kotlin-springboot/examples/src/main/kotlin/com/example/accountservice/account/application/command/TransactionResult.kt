package com.example.accountservice.account.application.command

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class TransactionResult(
    @field:Schema(description = "The transaction's ID (32-character hex, no hyphens).", example = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5")
    val transactionId: String,
    @field:Schema(description = "The account this transaction belongs to.", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    val accountId: String,
    @field:Schema(description = "The transaction type.", example = "DEPOSIT")
    val type: String,
    @field:Schema(description = "The transaction amount.")
    val amount: MoneyResult,
    @field:Schema(description = "When the transaction was recorded.")
    val createdAt: LocalDateTime,
) {
    data class MoneyResult(
        @field:Schema(description = "The amount, in the currency's smallest unit.", example = "10000")
        val amount: Long,
        @field:Schema(description = "The ISO 4217 currency code.", example = "USD")
        val currency: String,
    )
}
