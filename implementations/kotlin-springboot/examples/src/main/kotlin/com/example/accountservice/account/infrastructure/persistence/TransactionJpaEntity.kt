package com.example.accountservice.account.infrastructure.persistence

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
 * handled exclusively by TransactionMapper. Because a Transaction is immutable once created, it is used
 * only for inserts, with no mutable update methods.
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
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
