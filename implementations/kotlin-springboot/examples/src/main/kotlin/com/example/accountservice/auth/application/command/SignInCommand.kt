package com.example.accountservice.auth.application.command

data class SignInCommand(
    val userId: String,
    val password: String,
)
