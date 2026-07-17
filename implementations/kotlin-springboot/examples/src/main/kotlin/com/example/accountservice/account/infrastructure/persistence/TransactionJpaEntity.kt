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
 * account/domain/Transaction.kt의 JPA 매핑 전용 대응물.
 * Domain 하위 Entity(Transaction)는 이 클래스를 전혀 알지 못한다 — 변환은 TransactionMapper가 전담한다.
 * Transaction은 생성 후 불변이므로 갱신용 가변 메서드 없이 insert 전용으로만 쓰인다.
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
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)
