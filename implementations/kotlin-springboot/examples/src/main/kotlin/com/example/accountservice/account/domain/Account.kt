package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "accounts")
class Account protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var accountId: String = ""
        private set

    @Column(nullable = false)
    var ownerId: String = ""
        private set

    @Column(nullable = false)
    var email: String = ""
        private set

    @Embedded
    var balance: Money = Money(0, "")
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AccountStatus = AccountStatus.ACTIVE
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        private set

    @Column
    var deletedAt: LocalDateTime? = null
        private set

    @Transient
    private val domainEvents: MutableList<Any> = mutableListOf()

    @Transient
    private val pendingTransactions: MutableList<Transaction> = mutableListOf()

    companion object {
        fun create(ownerId: String, currency: String, email: String): Account =
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

    fun pullDomainEvents(): List<Any> = domainEvents.toList().also { domainEvents.clear() }

    fun pullPendingTransactions(): List<Transaction> = pendingTransactions.toList().also { pendingTransactions.clear() }
}
