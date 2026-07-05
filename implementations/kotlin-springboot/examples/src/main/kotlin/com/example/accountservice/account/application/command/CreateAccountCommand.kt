package com.example.accountservice.account.application.command

data class CreateAccountCommand(val requesterId: String, val currency: String)
