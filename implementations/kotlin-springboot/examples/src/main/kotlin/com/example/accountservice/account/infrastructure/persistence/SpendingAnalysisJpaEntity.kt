package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.common.nowUtc
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * The JPA-mapping counterpart of account/domain/SpendingAnalysis.kt. One row per (accountId,
 * analysisMonth) — the actual uniqueness backstop lives in the Flyway migration's UNIQUE index (see
 * V17__create_spending_analysis.sql), the same split AccountJpaEntity/TransactionJpaEntity use
 * (uniqueness enforced by migration SQL, not a JPA `@Table(uniqueConstraints = ...)` annotation).
 *
 * Like TransactionJpaEntity, this has no `deletedAt` column — a spending-analysis row is written once
 * by the ETL and never updated or deleted afterward, so there is no delete use case and therefore no
 * hard-delete risk to guard against (persistence.md, "an Entity with no delete use case has no
 * `deletedAt` column at all").
 */
@Entity
@Table(name = "spending_analysis")
class SpendingAnalysisJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var analysisId: String = "",
    @Column(nullable = false)
    var accountId: String = "",
    @Column(nullable = false)
    var analysisMonth: String = "",
    @Column(nullable = false)
    var totalAmount: Long = 0,
    @Column(nullable = false)
    var transactionCount: Long = 0,
    @Column(nullable = false)
    var averageAmount: Long = 0,
    @Column(nullable = false)
    var changeFromPreviousMonth: Long = 0,
    @Column(nullable = false)
    var trend: String = "",
    @Column(nullable = false)
    var createdAt: LocalDateTime = nowUtc(),
)
