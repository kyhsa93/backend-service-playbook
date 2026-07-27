package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.domain.SpendingAnalysis;

/**
 * The class dedicated to converting between SpendingAnalysis (pure domain) and
 * SpendingAnalysisJpaEntity (JPA mapping). It is used only inside SpendingAnalysisRepositoryImpl.
 * Since a SpendingAnalysis is immutable after creation, only insert-side conversion is needed.
 */
final class SpendingAnalysisMapper {

    private SpendingAnalysisMapper() {}

    static SpendingAnalysis toDomain(SpendingAnalysisJpaEntity entity) {
        return SpendingAnalysis.reconstitute(
                entity.getAnalysisId(),
                entity.getAccountId(),
                entity.getAnalysisMonth(),
                entity.getTotalAmount(),
                entity.getTransactionCount(),
                entity.getAverageAmount(),
                entity.getChangeFromPreviousMonth(),
                entity.getTrend(),
                entity.getCreatedAt());
    }

    static SpendingAnalysisJpaEntity toNewEntity(SpendingAnalysis analysis) {
        return new SpendingAnalysisJpaEntity(
                null,
                analysis.getAnalysisId(),
                analysis.getAccountId(),
                analysis.getAnalysisMonth(),
                analysis.getTotalAmount(),
                analysis.getTransactionCount(),
                analysis.getAverageAmount(),
                analysis.getChangeFromPreviousMonth(),
                analysis.getTrend(),
                analysis.getCreatedAt());
    }
}
