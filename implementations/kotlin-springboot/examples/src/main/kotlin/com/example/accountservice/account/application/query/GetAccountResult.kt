package com.example.accountservice.account.application.query

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GetAccountResult(
    @field:Schema(description = "The account's ID (32-character hex, no hyphens).", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    val accountId: String,
    @field:Schema(description = "The userId of the account owner.", example = "jane.doe")
    val ownerId: String,
    @field:Schema(description = "The account owner's email address.", example = "owner@example.com")
    val email: String,
    @field:Schema(description = "The current account balance.")
    val balance: MoneyResult,
    @field:Schema(description = "The account's lifecycle status.", example = "ACTIVE")
    val status: String,
    @field:Schema(description = "When the account was created.")
    val createdAt: LocalDateTime,
    @field:Schema(description = "When the account was last updated.")
    val updatedAt: LocalDateTime,
) {
    data class MoneyResult(
        @field:Schema(description = "The amount, in the currency's smallest unit.", example = "10000")
        val amount: Long,
        @field:Schema(description = "The ISO 4217 currency code.", example = "USD")
        val currency: String,
    )
}
