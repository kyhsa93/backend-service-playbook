package com.example.accountservice.payment.application.query

import java.time.LocalDateTime

data class GetPaymentResult(
    val paymentId: String,
    val cardId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val status: String,
    val createdAt: LocalDateTime,
)
