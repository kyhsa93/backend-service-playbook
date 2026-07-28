package com.example.accountservice.account.domain;

public interface SpendingForecastRepository {

    void saveForecast(SpendingForecast forecast);

    /**
     * A cheap idempotency check ahead of the real work — the (accountId, forecastMonth) unique
     * constraint on the table is the last line of defense, the same two-layer pattern as {@link
     * SpendingAnalysisRepository#hasAnalysis}.
     */
    boolean hasForecast(String accountId, String forecastMonth);
}
