package com.example.accountservice.payment.application.command

import java.time.LocalDateTime

data class RequestRefundResult(
    val refundId: String,
    val paymentId: String,
    val amount: Long,
    val reason: String,
    val status: String,
    val decisionNote: String?,
    val createdAt: LocalDateTime,
)
