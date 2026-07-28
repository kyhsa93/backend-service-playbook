package com.example.accountservice.account.application.query;

import com.example.accountservice.account.domain.SpendingForecast;
import java.util.Optional;

/**
 * A read-only interface dedicated to {@code GetSpendingForecastService} — kept separate from the
 * write-side {@code SpendingForecastRepository} (domain), the same narrow-contract convention as
 * {@code SpendingAnalysisQuery} (see cqrs-pattern.md).
 */
public interface SpendingForecastQuery {
    Optional<SpendingForecast> findForecast(String accountId, String forecastMonth);
}
