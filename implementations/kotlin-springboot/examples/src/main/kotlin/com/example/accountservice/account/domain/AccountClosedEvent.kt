package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountClosedEvent(
    val accountId: String,
    val email: String,
    val closedAt: LocalDateTime,
)
