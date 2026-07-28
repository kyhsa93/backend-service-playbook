package com.example.accountservice.account.domain

/**
 * The write-side port for [SpendingAnalysis] — a plain data holder rather than a full Aggregate, but
 * still gets its own Repository interface (domain/) + implementation (infrastructure/persistence/)
 * split, the same as [AccountRepository].
 */
interface SpendingAnalysisRepository {
    fun saveAnalysis(analysis: SpendingAnalysis)

    /**
     * A cheap idempotency check ahead of the real work — the (accountId, analysisMonth) unique
     * index on the table is the last line of defense, the same two-layer pattern as
     * `CardStatementNotificationService`/`Card.hasStatementSent`-style checks elsewhere in this
     * codebase.
     */
    fun hasAnalysis(
        accountId: String,
        analysisMonth: String,
    ): Boolean

    /**
     * The training data for `ForecastSpendingService` — every analysis row strictly before
     * [beforeMonth], capped at [limit], returned oldest-first (chronological order) since
     * `SpendingForecastModel.predict` treats list position as the month index.
     */
    fun findRecentAnalyses(
        accountId: String,
        beforeMonth: String,
        limit: Int,
    ): List<SpendingAnalysis>
}
