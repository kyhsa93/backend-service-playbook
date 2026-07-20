package com.example.accountservice.account.application.query

data class GetTransactionsResult(
    val transactions: List<String>,
    val count: Long,
)
