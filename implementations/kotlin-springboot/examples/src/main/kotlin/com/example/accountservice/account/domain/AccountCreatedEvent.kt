package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountCreatedEvent(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val currency: String,
    val createdAt: LocalDateTime,
)
