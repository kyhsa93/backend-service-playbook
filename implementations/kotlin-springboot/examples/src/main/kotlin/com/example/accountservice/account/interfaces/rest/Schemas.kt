package com.example.accountservice.account.interfaces.rest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateAccountRequest(
    @field:Schema(description = "The ISO 4217 currency code the account balance is denominated in.", example = "USD")
    val currency: String,
    @field:NotBlank
    @field:Email
    @field:Schema(description = "The account owner's email address, used for SES notifications.", example = "owner@example.com")
    val email: String,
)

data class DepositRequest(
    @field:Schema(description = "The amount to credit, in the account's smallest currency unit. Must be greater than 0.", example = "10000")
    val amount: Long,
)

data class WithdrawRequest(
    @field:Schema(description = "The amount to debit, in the account's smallest currency unit. Must be greater than 0.", example = "5000")
    val amount: Long,
)

data class TransferRequest(
    @field:Schema(description = "The accountId of the account receiving the funds.", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    val targetAccountId: String,
    @field:Schema(
        description = "The amount to transfer, in the account's smallest currency unit. Must be greater than 0.",
        example = "2500",
    )
    val amount: Long,
)

data class ErrorResponse(
    @field:Schema(description = "The HTTP status code.", example = "400")
    val statusCode: Int,
    @field:Schema(
        description = "A stable, machine-readable error code to branch on. Unlike `message`, it never changes wording or gets translated.",
        example = "VALIDATION_FAILED",
    )
    val code: String,
    @field:Schema(
        description = "A human-readable description of the error.",
        example = "Account not found: a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    )
    val message: String,
    @field:Schema(description = "The standard HTTP status text for `statusCode`.", example = "Bad Request")
    val error: String,
)
