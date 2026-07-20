package com.example.accountservice.account.application.query

data class GetTransactionsResult(
    val result: List<String>,
    val count: Long,
)
