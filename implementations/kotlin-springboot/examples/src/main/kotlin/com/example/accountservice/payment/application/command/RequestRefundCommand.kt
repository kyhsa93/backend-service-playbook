package com.example.accountservice.payment.application.command

data class RequestRefundCommand(
    val paymentId: String,
    val amount: Long,
    val reason: String,
    val requesterId: String,
)
