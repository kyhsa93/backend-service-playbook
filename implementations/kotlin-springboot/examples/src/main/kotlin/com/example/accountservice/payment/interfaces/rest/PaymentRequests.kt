package com.example.accountservice.payment.interfaces.rest

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

data class CreatePaymentRequest(
    @field:NotBlank
    @field:Schema(
        description = "The cardId to charge. The card must belong to the requester and be active.",
        example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4",
    )
    val cardId: String,
    @field:Min(1)
    @field:Schema(
        description = "The amount to charge, in the linked account's smallest currency unit. Must be at least 1.",
        example = "3000",
    )
    val amount: Long,
)

data class CancelPaymentRequest(
    @field:NotBlank
    @field:Schema(description = "Why the payment is being cancelled.", example = "Customer requested cancellation")
    val reason: String,
)

data class RequestRefundRequest(
    @field:Min(1)
    @field:Schema(description = "The amount to refund. Must be at least 1 and cannot exceed the original payment amount.", example = "1000")
    val amount: Long,
    @field:NotBlank
    @field:Schema(description = "Why the refund is being requested.", example = "Item was defective")
    val reason: String,
)
