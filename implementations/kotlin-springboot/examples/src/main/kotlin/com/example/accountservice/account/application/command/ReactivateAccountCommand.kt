package com.example.accountservice.account.application.command

data class ReactivateAccountCommand(val accountId: String, val requesterId: String)
