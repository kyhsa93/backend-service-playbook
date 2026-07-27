package com.example.accountservice.account.infrastructure.persistence;

import com.example.accountservice.account.application.query.SpendingAnalysisQuery;
import com.example.accountservice.account.domain.SpendingAnalysis;
import com.example.accountservice.account.domain.SpendingAnalysisRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements both the write-side {@link SpendingAnalysisRepository} and the read-side {@link
 * SpendingAnalysisQuery} for SpendingAnalysis in a single class — the same structure as
 * account/infrastructure/persistence/AccountRepositoryImpl. Each Application layer class only
 * injects the narrow interface it needs (Repository or Query).
 */
@Repository
@RequiredArgsConstructor
public class SpendingAnalysisRepositoryImpl
        implements SpendingAnalysisRepository, SpendingAnalysisQuery {

    private final SpendingAnalysisJpaRepository jpaRepository;

    @Override
    @Transactional
    public void saveAnalysis(SpendingAnalysis analysis) {
        jpaRepository.save(SpendingAnalysisMapper.toNewEntity(analysis));
    }

    @Override
    public boolean hasAnalysis(String accountId, String analysisMonth) {
        return jpaRepository.existsByAccountIdAndAnalysisMonth(accountId, analysisMonth);
    }

    @Override
    public Optional<SpendingAnalysis> findAnalysis(String accountId, String analysisMonth) {
        return jpaRepository
                .findByAccountIdAndAnalysisMonth(accountId, analysisMonth)
                .map(SpendingAnalysisMapper::toDomain);
    }
}
