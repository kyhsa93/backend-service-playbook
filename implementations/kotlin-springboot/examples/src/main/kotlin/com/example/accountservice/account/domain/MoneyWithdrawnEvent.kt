package com.example.accountservice.account.domain

import java.time.LocalDateTime

data class MoneyWithdrawnEvent(
    override val accountId: String,
    override val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val createdAt: LocalDateTime,
    // Carried through so CategorizeTransactionEventHandler doesn't need a separate lookup to react —
    // the same reasoning as every other field on this event. Absent when the requester didn't attach
    // one; CategorizeTransactionEventHandler skips categorization entirely in that case.
    val merchantName: String? = null,
) : DomainEvent
