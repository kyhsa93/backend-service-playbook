package com.example.accountservice.auth.application.command

data class SignUpCommand(
    val userId: String,
    val password: String,
)
