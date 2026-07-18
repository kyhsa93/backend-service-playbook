package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class MoneyWithdrawnEvent(
    override val accountId: String,
    override val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val createdAt: LocalDateTime,
) : DomainEvent
