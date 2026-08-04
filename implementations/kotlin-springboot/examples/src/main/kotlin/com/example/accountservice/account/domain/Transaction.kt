package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import com.example.accountservice.common.nowUtc
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

    var createdAt: LocalDateTime = nowUtc()
        private set

    // An optional field that lets a transaction created in reaction to an external BC's (Payment)
    // Integration Event be correlated with another BC's Aggregate ID (paymentId/refundId). It is absent
    // (null) for deposits/withdrawals the user requested directly (DepositService/WithdrawService) — it
    // is populated only by WithdrawByPaymentService/DepositByPaymentService, and together with type it
    // serves as the Level 2 Ledger key that prevents duplicate processing on an at-least-once
    // redelivery (see domain-events.md, "Event handler idempotency").
    var referenceId: String? = null
        private set

    // The payee/memo the requester optionally attaches to a withdrawal — the only free-text signal
    // TransactionAutoCategorizer has to classify against. Absent for deposits/interest and for a
    // withdrawal the requester didn't attach one to.
    var merchantName: String? = null
        private set

    // Filled in asynchronously, after the transaction is created — CategorizeTransactionEventHandler
    // reacts to MoneyWithdrawnEvent and categorizes it later, so this is always null at the moment
    // Account.withdraw() constructs the Transaction, and only present when this object is
    // reconstituted from a row that a categorization run has already updated.
    var category: TransactionCategory? = null
        private set

    companion object {
        fun create(
            accountId: String,
            type: TransactionType,
            amount: Money,
            referenceId: String? = null,
            merchantName: String? = null,
        ): Transaction =
            Transaction().apply {
                this.transactionId = generateId()
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.merchantName = merchantName
                this.createdAt = nowUtc()
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
            merchantName: String? = null,
            category: TransactionCategory? = null,
        ): Transaction =
            Transaction().apply {
                this.transactionId = transactionId
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.referenceId = referenceId
                this.merchantName = merchantName
                this.category = category
                this.createdAt = createdAt
            }
    }

    /**
     * The domain method CategorizeTransactionEventHandler drives TransactionRepository's
     * find→modify→save<Noun> cycle through (see docs/architecture/repository-pattern.md) —
     * Transaction is otherwise immutable, so this returns a new instance rather than mutating in
     * place.
     */
    fun categorize(category: TransactionCategory): Transaction =
        reconstitute(
            transactionId = transactionId,
            accountId = accountId,
            type = type,
            amount = amount,
            referenceId = referenceId,
            createdAt = createdAt,
            merchantName = merchantName,
            category = category,
        )
}
