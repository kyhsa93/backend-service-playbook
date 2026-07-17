package com.example.accountservice.account.application.command

data class WithdrawCommand(
    val accountId: String,
    val requesterId: String,
    val amount: Long,
)
