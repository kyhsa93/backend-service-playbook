package com.example.accountservice.payment.application.command

import java.time.LocalDateTime

data class CreatePaymentResult(
    val paymentId: String,
    val cardId: String,
    val accountId: String,
    val ownerId: String,
    val amount: Long,
    val status: String,
    val createdAt: LocalDateTime,
)
