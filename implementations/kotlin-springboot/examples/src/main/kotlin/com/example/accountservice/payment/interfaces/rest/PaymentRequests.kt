package com.example.accountservice.payment.interfaces.rest

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreatePaymentRequest(
    @field:NotBlank
    val cardId: String,
    @field:Min(1)
    val amount: Long,
)

data class CancelPaymentRequest(
    @field:NotBlank
    val reason: String,
)

data class RequestRefundRequest(
    @field:Min(1)
    val amount: Long,
    @field:NotBlank
    val reason: String,
)
