package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Account Aggregate Root — a pure Kotlin object with no dependency on any framework/ORM.
 * Persistence mapping is handled exclusively by infrastructure/persistence/AccountJpaEntity + AccountMapper.
 */
class Account private constructor() {
    var accountId: String = ""
        private set

    var ownerId: String = ""
        private set

    var email: String = ""
        private set

    var balance: Money = Money(0, "")
        private set

    var status: AccountStatus = AccountStatus.ACTIVE
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    var updatedAt: LocalDateTime = LocalDateTime.now()
        private set

    var deletedAt: LocalDateTime? = null
        private set

    // The Level 1 (essential idempotency) key for the recurring interest payment (PayInterestService) —
    // the Account itself knows "whether it already received interest today," so even if the Task runs
    // twice in the same day under at-least-once execution, the second call is skipped with no side
    // effects (no separate Ledger table needed, domain-events.md idempotency step 1).
    var lastInterestPaidAt: LocalDate? = null
        private set

    private val domainEvents: MutableList<DomainEvent> = mutableListOf()

    private val pendingTransactions: MutableList<Transaction> = mutableListOf()

    companion object {
        // A daily interest rate of 0.01% — a constant used only for the recurring interest payment
        // (payInterest). The Aggregate holds this value itself rather than having it injected by the
        // Application layer (Scheduler/Command Service) — "how much interest to pay" is a policy value
        // rather than an Account invariant, but at this repository's scale there is no real benefit to
        // splitting it out into a separate policy table/config, so it is kept as a domain constant
        // (YAGNI).
        private val DAILY_INTEREST_RATE = BigDecimal("0.0001")

        fun create(
            ownerId: String,
            currency: String,
            email: String,
        ): Account =
            Account().apply {
                this.accountId = generateId()
                this.ownerId = ownerId
                this.email = email
                this.balance = Money(0, currency)
                this.status = AccountStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
                this.updatedAt = this.createdAt
                this.domainEvents += AccountCreatedEvent(this.accountId, ownerId, email, currency, this.createdAt)
            }

        /**
         * Used by a Repository implementation to reconstitute an Account from persisted data (a JPA
         * entity, etc.). Unlike create(), it does not generate domain events — it merely reconstitutes
         * a state that was already committed in the past.
         */
        fun reconstitute(
            accountId: String,
            ownerId: String,
            email: String,
            balance: Money,
            status: AccountStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
            deletedAt: LocalDateTime?,
            lastInterestPaidAt: LocalDate? = null,
        ): Account =
            Account().apply {
                this.accountId = accountId
                this.ownerId = ownerId
                this.email = email
                this.balance = balance
                this.status = status
                this.createdAt = createdAt
                this.updatedAt = updatedAt
                this.deletedAt = deletedAt
                this.lastInterestPaidAt = lastInterestPaidAt
            }
    }

    fun deposit(
        amount: Long,
        referenceId: String? = null,
    ): Transaction {
        if (status != AccountStatus.ACTIVE) throw DepositRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        balance = balance.add(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.DEPOSIT, money, referenceId)
        pendingTransactions += transaction
        domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    fun withdraw(
        amount: Long,
        referenceId: String? = null,
    ): Transaction {
        if (status != AccountStatus.ACTIVE) throw WithdrawRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        if (balance.isLessThan(money)) throw InsufficientBalanceException()
        balance = balance.subtract(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.WITHDRAWAL, money, referenceId)
        pendingTransactions += transaction
        domainEvents += MoneyWithdrawnEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    /**
     * Recurring interest payment (system-initiated, called by PayInterestTaskController →
     * PayInterestService) — since it is not a user Command, unlike deposit() it does not throw an
     * exception and instead returns `null` when "there is nothing to apply." PayInterestService iterates
     * over thousands of accounts within a single Task, so the whole batch must not abort with an
     * exception just because one account was already processed (e.g. a state change from a race) or its
     * interest amount is zero — this is a different requirement from deposit()/withdraw()'s "an invalid
     * command fails immediately."
     *
     * Idempotency only requires checking whether [lastInterestPaidAt] equals [payDate] (Level 1,
     * essential idempotency) — even if this is called twice for the same date (at-least-once
     * redelivery), the second call changes nothing and returns `null`. The caller (PayInterestService)
     * also filters out already-paid accounts at the query stage via
     * `AccountFindQuery.excludeInterestPaidDate`, but this method itself re-checks defensively as well.
     */
    fun payInterest(payDate: LocalDate): Transaction? {
        if (status != AccountStatus.ACTIVE) return null
        if (lastInterestPaidAt == payDate) return null
        val interestAmount =
            BigDecimal(balance.amount)
                .multiply(DAILY_INTEREST_RATE)
                .setScale(0, RoundingMode.FLOOR)
                .toLong()
        if (interestAmount <= 0) return null

        val money = Money(interestAmount, balance.currency)
        balance = balance.add(money)
        updatedAt = LocalDateTime.now()
        lastInterestPaidAt = payDate
        val transaction = Transaction.create(accountId, TransactionType.INTEREST, money)
        pendingTransactions += transaction
        domainEvents += InterestPaidEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    fun suspend() {
        if (status != AccountStatus.ACTIVE) throw SuspendRequiresActiveAccountException()
        status = AccountStatus.SUSPENDED
        updatedAt = LocalDateTime.now()
        domainEvents += AccountSuspendedEvent(accountId, email, updatedAt)
    }

    fun reactivate() {
        if (status != AccountStatus.SUSPENDED) throw ReactivateRequiresSuspendedAccountException()
        status = AccountStatus.ACTIVE
        updatedAt = LocalDateTime.now()
        domainEvents += AccountReactivatedEvent(accountId, email, updatedAt)
    }

    fun close() {
        if (status == AccountStatus.CLOSED) throw AccountAlreadyClosedException()
        if (!balance.isZero()) throw AccountBalanceNotZeroException()
        status = AccountStatus.CLOSED
        updatedAt = LocalDateTime.now()
        domainEvents += AccountClosedEvent(accountId, email, updatedAt)
    }

    fun markDeleted() {
        if (status != AccountStatus.CLOSED) throw DeleteRequiresClosedAccountException()
        deletedAt = LocalDateTime.now()
        updatedAt = deletedAt!!
    }

    fun pullDomainEvents(): List<DomainEvent> = domainEvents.toList().also { domainEvents.clear() }

    fun pullPendingTransactions(): List<Transaction> = pendingTransactions.toList().also { pendingTransactions.clear() }
}
