package com.example.accountservice.account.application.command

data class TransferCommand(
    val sourceAccountId: String,
    val targetAccountId: String,
    val requesterId: String,
    val amount: Long,
)
