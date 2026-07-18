package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountCreatedEvent(
    override val accountId: String,
    val ownerId: String,
    override val email: String,
    val currency: String,
    val createdAt: LocalDateTime,
) : DomainEvent
