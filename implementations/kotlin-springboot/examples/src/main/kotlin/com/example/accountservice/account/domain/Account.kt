package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * Account Aggregate Root — 어떤 프레임워크/ORM에도 의존하지 않는 순수 Kotlin 객체.
 * 영속성 매핑은 infrastructure/persistence/AccountJpaEntity + AccountMapper가 전담한다.
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

    private val domainEvents: MutableList<DomainEvent> = mutableListOf()

    private val pendingTransactions: MutableList<Transaction> = mutableListOf()

    companion object {
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
         * Repository 구현체가 영속 데이터(JPA 엔티티 등)로부터 Account를 복원할 때 사용한다.
         * create()와 달리 도메인 이벤트를 생성하지 않는다 — 이미 과거에 커밋된 상태를 그대로 재구성하는 것뿐이다.
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
            }
    }

    fun deposit(amount: Long): Transaction {
        if (status != AccountStatus.ACTIVE) throw DepositRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        balance = balance.add(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.DEPOSIT, money)
        pendingTransactions += transaction
        domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
        return transaction
    }

    fun withdraw(amount: Long): Transaction {
        if (status != AccountStatus.ACTIVE) throw WithdrawRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        if (balance.isLessThan(money)) throw InsufficientBalanceException()
        balance = balance.subtract(money)
        updatedAt = LocalDateTime.now()
        val transaction = Transaction.create(accountId, TransactionType.WITHDRAWAL, money)
        pendingTransactions += transaction
        domainEvents += MoneyWithdrawnEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
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
