package com.example.accountservice.account.domain

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "transactions")
class Transaction protected constructor() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var transactionId: String = ""
        private set

    @Column(nullable = false)
    var accountId: String = ""
        private set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: TransactionType = TransactionType.DEPOSIT
        private set

    @Embedded
    var amount: Money = Money(0, "")
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun create(accountId: String, type: TransactionType, amount: Money): Transaction =
            Transaction().apply {
                this.transactionId = UUID.randomUUID().toString()
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.createdAt = LocalDateTime.now()
            }
    }
}
