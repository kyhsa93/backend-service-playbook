package com.example.accountservice.account.interfaces.rest

data class ErrorResponse(
    val statusCode: String,
    val code: String,
    val message: String,
    val error: String,
)
