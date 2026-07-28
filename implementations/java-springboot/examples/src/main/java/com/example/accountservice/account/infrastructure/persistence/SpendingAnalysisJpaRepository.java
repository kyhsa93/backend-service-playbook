package com.example.accountservice.account.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpendingAnalysisJpaRepository
        extends JpaRepository<SpendingAnalysisJpaEntity, Long> {

    boolean existsByAccountIdAndAnalysisMonth(String accountId, String analysisMonth);

    Optional<SpendingAnalysisJpaEntity> findByAccountIdAndAnalysisMonth(
            String accountId, String analysisMonth);

    // Most-recent-first, capped by the Pageable — SpendingAnalysisRepositoryImpl reverses this to
    // chronological order before returning it (see
    // domain/SpendingAnalysisRepository#findRecentAnalyses).
    List<SpendingAnalysisJpaEntity> findByAccountIdAndAnalysisMonthLessThanOrderByAnalysisMonthDesc(
            String accountId, String beforeMonth, Pageable pageable);
}
