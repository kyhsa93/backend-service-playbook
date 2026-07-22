package com.example.accountservice.account.application.command

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class CreateAccountResult(
    @field:Schema(
        description = "The newly created account's ID (32-character hex, no hyphens).",
        example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    )
    val accountId: String,
    @field:Schema(description = "The userId of the account owner.", example = "jane.doe")
    val ownerId: String,
    @field:Schema(description = "The account owner's email address.", example = "owner@example.com")
    val email: String,
    @field:Schema(description = "The account balance. Always 0 on creation.")
    val balance: MoneyResult,
    @field:Schema(description = "The account's lifecycle status.", example = "ACTIVE")
    val status: String,
    @field:Schema(description = "When the account was created.")
    val createdAt: LocalDateTime,
) {
    data class MoneyResult(
        @field:Schema(description = "The amount, in the currency's smallest unit.", example = "0")
        val amount: Long,
        @field:Schema(description = "The ISO 4217 currency code.", example = "USD")
        val currency: String,
    )
}
