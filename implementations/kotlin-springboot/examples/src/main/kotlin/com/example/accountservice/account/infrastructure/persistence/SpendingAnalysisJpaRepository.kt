package com.example.accountservice.account.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface SpendingAnalysisJpaRepository : JpaRepository<SpendingAnalysisJpaEntity, Long> {
    fun existsByAccountIdAndAnalysisMonth(
        accountId: String,
        analysisMonth: String,
    ): Boolean

    fun findByAccountIdAndAnalysisMonth(
        accountId: String,
        analysisMonth: String,
    ): SpendingAnalysisJpaEntity?

    // Used only by TaskQueueE2ETest to assert re-enqueueing the same month never produces a second row.
    fun countByAccountIdAndAnalysisMonth(
        accountId: String,
        analysisMonth: String,
    ): Long
}
