package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountSuspendedEvent(
    override val accountId: String,
    override val email: String,
    val suspendedAt: LocalDateTime,
) : DomainEvent
