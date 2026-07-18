package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountReactivatedEvent(
    override val accountId: String,
    override val email: String,
    val reactivatedAt: LocalDateTime,
) : DomainEvent
