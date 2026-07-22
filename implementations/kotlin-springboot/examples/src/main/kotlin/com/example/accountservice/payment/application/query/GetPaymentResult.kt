package com.example.accountservice.payment.application.query

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class GetPaymentResult(
    @field:Schema(description = "The payment's ID (32-character hex, no hyphens).", example = "e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
    val paymentId: String,
    @field:Schema(description = "The card that was charged.", example = "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1")
    val cardId: String,
    @field:Schema(description = "The account linked to the charged card.", example = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
    val accountId: String,
    @field:Schema(description = "The userId of the payment owner.", example = "jane.doe")
    val ownerId: String,
    @field:Schema(description = "The charged amount.", example = "3000")
    val amount: Long,
    @field:Schema(description = "The payment's lifecycle status.", example = "COMPLETED")
    val status: String,
    @field:Schema(description = "When the payment was created.")
    val createdAt: LocalDateTime,
)
