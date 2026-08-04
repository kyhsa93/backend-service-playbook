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
 * The JPA-mapping counterpart of account/domain/SpendingForecast.kt. One row per (accountId,
 * forecastMonth) — the actual uniqueness backstop lives in the Flyway migration's UNIQUE
 * constraint (see V18__create_spending_forecast.sql), the same split
 * SpendingAnalysisJpaEntity uses.
 *
 * Insert-only, like SpendingAnalysisJpaEntity: a row is written once by the batch job and never
 * updated or deleted afterward, so there is no `deletedAt` column at all (persistence.md, "an
 * Entity with no delete use case has no `deletedAt` column at all").
 */
@Entity
@Table(name = "spending_forecast")
class SpendingForecastJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(nullable = false, unique = true)
    var forecastId: String = "",
    @Column(nullable = false)
    var accountId: String = "",
    @Column(nullable = false)
    var forecastMonth: String = "",
    @Column(nullable = false)
    var predictedAmount: Long = 0,
    @Column(nullable = false)
    var confidence: String = "",
    @Column(nullable = false)
    var historyMonthsUsed: Int = 0,
    @Column(nullable = false)
    var createdAt: LocalDateTime = nowUtc(),
)
