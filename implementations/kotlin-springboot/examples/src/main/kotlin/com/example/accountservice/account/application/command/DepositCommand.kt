package com.example.accountservice.account.application.command

data class DepositCommand(
    val accountId: String,
    val requesterId: String,
    val amount: Long,
)
