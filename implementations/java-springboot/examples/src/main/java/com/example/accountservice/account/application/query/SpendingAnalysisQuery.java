package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.SpendingAnalysis;
import java.util.Optional;

/**
 * A read-only interface dedicated to {@code GetSpendingAnalysisService} — kept separate from the
 * write-side {@code SpendingAnalysisRepository} (domain), the same narrow-contract convention as
 * {@code AccountQuery} (see cqrs-pattern.md).
 */
public interface SpendingAnalysisQuery {
    Optional<SpendingAnalysis> findAnalysis(String accountId, String analysisMonth);
}
