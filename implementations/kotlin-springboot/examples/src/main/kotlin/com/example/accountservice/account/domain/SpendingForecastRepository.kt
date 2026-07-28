package com.example.accountservice.account.domain

/**
 * The write-side port for [SpendingForecast] — a plain data holder rather than a full Aggregate, but
 * still gets its own Repository interface (domain/) + implementation (infrastructure/persistence/)
 * split, the same as [SpendingAnalysisRepository].
 */
interface SpendingForecastRepository {
    fun saveForecast(forecast: SpendingForecast)

    /**
     * A cheap idempotency check ahead of the real work — the (accountId, forecastMonth) unique
     * constraint on the table is the last line of defense, the same two-layer pattern as
     * [SpendingAnalysisRepository.hasAnalysis].
     */
    fun hasForecast(
        accountId: String,
        forecastMonth: String,
    ): Boolean
}
