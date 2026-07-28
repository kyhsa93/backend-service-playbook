package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.TransactionCategory
import com.example.accountservice.account.domain.TransactionType
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA-mapping counterpart of account/domain/Transaction.kt.
 * The domain child Entity (Transaction) has no awareness of this class whatsoever — conversion is
 * handled exclusively by TransactionMapper. A Transaction is otherwise immutable once created — the
 * one exception is `category`, which TransactionRepositoryImpl updates in place once, asynchronously,
 * via the find→Transaction.categorize()→save cycle (see domain/TransactionRepository.kt).
 */
@Entity
@Table(name = "transactions")
class TransactionJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var transactionId: String = "",
    @Column(nullable = false)
    var accountId: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var type: TransactionType = TransactionType.DEPOSIT,
    @Embedded
    var amount: MoneyEmbeddable = MoneyEmbeddable(),
    @Column
    var referenceId: String? = null,
    // The payee/memo optionally attached to a withdrawal at request time — see domain/Transaction.kt.
    @Column
    var merchantName: String? = null,
    // Filled in asynchronously by CategorizeTransactionEventHandler — null until that reaction runs
    // (or forever, for a transaction with no merchantName to classify).
    @Enumerated(EnumType.STRING)
    @Column
    var category: TransactionCategory? = null,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
