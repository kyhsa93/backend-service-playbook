package com.example.accountservice.account.application.query

import com.example.accountservice.account.domain.SpendingAnalysis

/**
 * A read-only port for [SpendingAnalysis] — the same separation `AccountQuery` gives `Account`
 * (kept apart from the write-model `SpendingAnalysisRepository` so a Query Service can never reach a
 * write method, the harness's `cqrs-pattern` rule enforces this at compile time).
 *
 * Reuses the `find<Noun>s` naming from repository-pattern.md — a single-record lookup by
 * (accountId, analysisMonth) is handled via `.firstOrNull()` over the returned list, the same idiom
 * `GetAccountService`/`GetTransactionsService` already use, rather than a dedicated single-item method.
 */
interface SpendingAnalysisQuery {
    fun findAnalyses(query: SpendingAnalysisFindQuery): List<SpendingAnalysis>
}

data class SpendingAnalysisFindQuery(
    val accountId: String,
    val analysisMonth: String,
)
