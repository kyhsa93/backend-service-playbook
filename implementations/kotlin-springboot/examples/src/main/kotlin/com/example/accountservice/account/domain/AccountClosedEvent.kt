package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class AccountClosedEvent(
    override val accountId: String,
    override val email: String,
    val closedAt: LocalDateTime,
) : DomainEvent
