package com.example.accountservice.account.domain

import java.time.LocalDateTime

/**
 * A Domain Event published by [Account.payInterest] when interest is actually credited (only when the
 * amount is non-zero). It has the same shape as MoneyDepositedEvent, but interest payment is triggered by
 * the system (Scheduler → Task Queue) rather than by a user Command, so it is kept as a separate event
 * type (scheduling.md — a Task Queue entry is "a command: perform X," while a Domain Event is "a fact: X
 * happened"; this event is the fact published by the Aggregate as a result of that Task's execution).
 */
data class InterestPaidEvent(
    override val accountId: String,
    override val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val paidAt: LocalDateTime,
) : DomainEvent
