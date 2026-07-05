package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class MoneyDepositedEvent(
    val accountId: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val createdAt: LocalDateTime,
)
