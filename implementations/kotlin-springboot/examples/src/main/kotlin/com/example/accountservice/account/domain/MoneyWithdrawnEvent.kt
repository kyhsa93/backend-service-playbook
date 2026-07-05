package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class MoneyWithdrawnEvent(
    val accountId: String,
    val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val createdAt: LocalDateTime,
)
