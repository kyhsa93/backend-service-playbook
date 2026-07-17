package com.example.accountservice.account.application.command

data class DeleteAccountCommand(
    val accountId: String,
    val requesterId: String,
)
