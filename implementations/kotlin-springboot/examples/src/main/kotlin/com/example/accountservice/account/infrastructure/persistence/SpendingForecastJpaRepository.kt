package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SpendingForecastJpaRepository : JpaRepository<SpendingForecastJpaEntity, Long> {
    fun existsByAccountIdAndForecastMonth(
        accountId: String,
        forecastMonth: String,
    ): Boolean

    fun findByAccountIdAndForecastMonth(
        accountId: String,
        forecastMonth: String,
    ): SpendingForecastJpaEntity?

    // Used only by TaskQueueE2ETest to assert re-enqueueing the same month never produces a second row.
    fun countByAccountIdAndForecastMonth(
        accountId: String,
        forecastMonth: String,
    ): Long
}
