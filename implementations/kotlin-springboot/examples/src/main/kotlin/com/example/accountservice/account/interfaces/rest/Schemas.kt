package com.example.accountservice.account.interfaces.rest

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateAccountRequest(
    val currency: String,
    @field:NotBlank
    @field:Email
    val email: String,
)

data class DepositRequest(val amount: Long)

data class WithdrawRequest(val amount: Long)

data class ErrorResponse(
    val statusCode: Int,
    val code: String,
    val message: String,
    val error: String,
)
