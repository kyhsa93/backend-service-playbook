package com.example.accountservice.payment.application.command

data class CreatePaymentCommand(
    val cardId: String,
    val amount: Long,
    val requesterId: String,
)
