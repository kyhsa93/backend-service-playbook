package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.SpendingForecast

/**
 * A read-only port for [SpendingForecast] — the same separation `AccountQuery` gives `Account`
 * (kept apart from the write-model `SpendingForecastRepository` so a Query Service can never reach
 * a write method, the harness's `cqrs-pattern` rule enforces this at compile time).
 *
 * Reuses the `find<Noun>s` naming from repository-pattern.md — a single-record lookup by
 * (accountId, forecastMonth) is handled via `.firstOrNull()` over the returned list, the same idiom
 * `SpendingAnalysisQuery`/`GetAccountService`/`GetTransactionsService` already use, rather than a
 * dedicated single-item method.
 */
interface SpendingForecastQuery {
    fun findForecasts(query: SpendingForecastFindQuery): List<SpendingForecast>
}

data class SpendingForecastFindQuery(
    val accountId: String,
    val forecastMonth: String,
)
