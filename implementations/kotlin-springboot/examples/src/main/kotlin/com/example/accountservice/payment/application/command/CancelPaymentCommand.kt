package com.example.accountservice.payment.application.command

data class CancelPaymentCommand(
    val paymentId: String,
    val reason: String,
    val requesterId: String,
)
