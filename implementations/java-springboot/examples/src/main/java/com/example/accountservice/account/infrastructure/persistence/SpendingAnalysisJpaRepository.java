package com.example.accountservice.account.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpendingAnalysisJpaRepository
        extends JpaRepository<SpendingAnalysisJpaEntity, Long> {

    boolean existsByAccountIdAndAnalysisMonth(String accountId, String analysisMonth);

    Optional<SpendingAnalysisJpaEntity> findByAccountIdAndAnalysisMonth(
            String accountId, String analysisMonth);
}
