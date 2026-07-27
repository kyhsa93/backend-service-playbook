package com.example.accountservice.account.infrastructure.persistence

import com.example.accountservice.account.domain.SpendingAnalysis
import com.example.accountservice.account.domain.SpendingTrend

/**
 * Converts between SpendingAnalysis (pure domain) and SpendingAnalysisJpaEntity (JPA mapping). Used
 * only inside SpendingAnalysisRepositoryImpl. There is no `updateEntity` (unlike AccountMapper) —
 * a SpendingAnalysis row is write-once, the same reasoning TransactionMapper has no update method.
 */
internal object SpendingAnalysisMapper {
    fun toDomain(entity: SpendingAnalysisJpaEntity): SpendingAnalysis =
        SpendingAnalysis.reconstitute(
            analysisId = entity.analysisId,
            accountId = entity.accountId,
            analysisMonth = entity.analysisMonth,
            totalAmount = entity.totalAmount,
            transactionCount = entity.transactionCount,
            averageAmount = entity.averageAmount,
            changeFromPreviousMonth = entity.changeFromPreviousMonth,
            trend = SpendingTrend.valueOf(entity.trend),
            createdAt = entity.createdAt,
        )

    fun toNewEntity(analysis: SpendingAnalysis): SpendingAnalysisJpaEntity =
        SpendingAnalysisJpaEntity(
            id = null,
            analysisId = analysis.analysisId,
            accountId = analysis.accountId,
            analysisMonth = analysis.analysisMonth,
            totalAmount = analysis.totalAmount,
            transactionCount = analysis.transactionCount,
            averageAmount = analysis.averageAmount,
            changeFromPreviousMonth = analysis.changeFromPreviousMonth,
            trend = analysis.trend.name,
            createdAt = analysis.createdAt,
        )
}
