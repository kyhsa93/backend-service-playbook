package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountReactivatedEvent(
    val accountId: String,
    val email: String,
    val reactivatedAt: LocalDateTime,
)
