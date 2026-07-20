package com.example.accountservice.payment.application.query

data class GetPaymentsResult(
    val data: List<String>,
    val count: Long,
)
