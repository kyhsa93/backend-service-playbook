package com.example.accountservice.account.application.command

data class SuspendAccountCommand(val accountId: String, val requesterId: String)
