package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.AccountStatus
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * account/domain/Account.kt의 JPA 매핑 전용 대응물.
 * Domain Aggregate(Account)는 이 클래스를 전혀 알지 못한다 — 변환은 AccountMapper가 전담한다(layer-architecture.md 참고).
 *
 * 프로퍼티를 `var` + 기본값으로 선언해 Hibernate가 요구하는 기본 생성자를 kotlin-jpa 플러그인이 생성하게 하고,
 * 갱신 시 AccountMapper가 기존 행(PK 보존)의 가변 필드를 그대로 덮어쓸 수 있게 한다.
 */
@Entity
@Table(name = "accounts")
class AccountJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var accountId: String = "",
    @Column(nullable = false)
    var ownerId: String = "",
    @Column(nullable = false)
    var email: String = "",
    @Embedded
    var balance: MoneyEmbeddable = MoneyEmbeddable(),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: AccountStatus = AccountStatus.ACTIVE,
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column
    var deletedAt: LocalDateTime? = null,
    @Column
    var lastInterestPaidAt: LocalDate? = null,
)
