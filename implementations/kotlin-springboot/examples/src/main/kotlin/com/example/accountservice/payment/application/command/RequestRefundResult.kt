package com.example.accountservice.payment.application.command

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class RequestRefundResult(
    @field:Schema(
        description = "The newly created refund's ID (32-character hex, no hyphens).",
        example = "f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3",
    )
    val refundId: String,
    @field:Schema(description = "The payment this refund was requested against.", example = "e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")
    val paymentId: String,
    @field:Schema(description = "The requested refund amount.", example = "1000")
    val amount: Long,
    @field:Schema(description = "Why the refund was requested.", example = "Item was defective")
    val reason: String,
    @field:Schema(description = "Whether the refund was approved or rejected.", example = "APPROVED")
    val status: String,
    @field:Schema(description = "The reason the refund was rejected, if it was. `null` when approved.", nullable = true, example = "null")
    val decisionNote: String?,
    @field:Schema(description = "When the refund was requested.")
    val createdAt: LocalDateTime,
)
