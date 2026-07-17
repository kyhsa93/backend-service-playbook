package com.example.accountservice.account.application.command

data class CloseAccountCommand(
    val accountId: String,
    val requesterId: String,
)
