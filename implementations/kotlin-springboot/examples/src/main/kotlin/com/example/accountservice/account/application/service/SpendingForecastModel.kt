package com.example.accountservice.account.application.service

import com.example.accountservice.account.domain.ForecastConfidence

/**
 * One month's worth of the training signal — reuses the spending_analysis read-model the
 * account.analyze-monthly-spending ETL already produces, rather than re-aggregating raw
 * Transaction rows.
 */
data class SpendingHistoryPoint(
    val analysisMonth: String,
    val totalAmount: Long,
)

data class SpendingForecastPrediction(
    val predictedAmount: Long,
    val confidence: ForecastConfidence,
)

/**
 * A Technical Service (see root docs/architecture/domain-service.md) — the core of this feature is
 * a statistical model, an implementation concern independent of any domain rule, so it's abstracted
 * the same way [com.example.accountservice.account.application.service.NlTransactionQueryTranslator]
 * abstracts an LLM call. The interface takes/returns plain data only, never a JPA entity or an
 * account/domain Aggregate type, so the implementation (currently an in-process regression) could
 * later be swapped for a call to an external ML service without touching any caller.
 */
interface SpendingForecastModel {
    /**
     * [history] must be in chronological (oldest-first) order. Callers are expected to enforce a
     * minimum history length before calling this — see `MIN_HISTORY_MONTHS_FOR_FORECAST` in
     * `ForecastSpendingService`.
     */
    fun predict(history: List<SpendingHistoryPoint>): SpendingForecastPrediction
}
