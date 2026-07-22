package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * A child Entity of the Account Aggregate — a pure Kotlin object with no dependency on any
 * framework/ORM. Persistence mapping is handled exclusively by
 * infrastructure/persistence/TransactionJpaEntity + TransactionMapper.
 */
class Transaction private constructor() {
    var transactionId: String = ""
        private set

    var accountId: String = ""
        private set

    var type: TransactionType = TransactionType.DEPOSIT
        private set

    var amount: Money = Money(0, "")
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    // An optional field that lets a transaction created in reaction to an external BC's (Payment)
    // Integration Event be correlated with another BC's Aggregate ID (paymentId/refundId). It is absent
    // (null) for deposits/withdrawals the user requested directly (DepositService/WithdrawService) — it
    // is populated only by WithdrawByPaymentService/DepositByPaymentService, and together with type it
    // serves as the Level 2 Ledger key that prevents duplicate processing on an at-least-once
    // redelivery (see domain-events.md, "Event handler idempotency").
    var referenceId: String? = null
        private set

    companion object {
        fun create(
            accountId: String,
            type: TransactionType,
            amount: Money,
            referenceId: String? = null,
        ): Transaction =
            Transaction().apply {
                this.transactionId = generateId()
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Used by a Repository implementation to reconstitute a Transaction from persisted data (a JPA
         * entity, etc.).
         */
        fun reconstitute(
            transactionId: String,
            accountId: String,
            type: TransactionType,
            amount: Money,
            referenceId: String?,
            createdAt: LocalDateTime,
        ): Transaction =
            Transaction().apply {
                this.transactionId = transactionId
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.createdAt = createdAt
            }
    }
}
