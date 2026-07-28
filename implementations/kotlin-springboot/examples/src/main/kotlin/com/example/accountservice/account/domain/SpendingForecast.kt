package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime

/**
 * A materialized read-model row — the ETL's precomputed answer to "what will this account likely
 * spend next month," produced monthly by
 * [com.example.accountservice.account.application.command.ForecastSpendingService] (which trains a
 * fresh `SpendingForecastModel` from the account's own spending_analysis history on every run) and
 * served as-is by [com.example.accountservice.account.application.query.GetSpendingForecastService].
 * No business invariant lives here — the one real transform step (fitting the model) already
 * happened before this object is constructed — so this stays a plain data holder, the same reasoning
 * as [SpendingAnalysis]. Still follows the `private constructor` + `private set` idiom other domain
 * objects use so the harness's `aggregate-no-public-setters` rule applies uniformly, even though
 * nothing here is ever mutated after creation.
 */
class SpendingForecast private constructor() {
    var forecastId: String = ""
        private set

    var accountId: String = ""
        private set

    var forecastMonth: String = ""
        private set

    var predictedAmount: Long = 0
        private set

    var confidence: ForecastConfidence = ForecastConfidence.LOW
        private set

    var historyMonthsUsed: Int = 0
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun create(
            accountId: String,
            forecastMonth: String,
            predictedAmount: Long,
            confidence: ForecastConfidence,
            historyMonthsUsed: Int,
        ): SpendingForecast =
            SpendingForecast().apply {
                this.forecastId = generateId()
                this.accountId = accountId
                this.forecastMonth = forecastMonth
                this.predictedAmount = predictedAmount
                this.confidence = confidence
                this.historyMonthsUsed = historyMonthsUsed
                this.createdAt = LocalDateTime.now()
            }

        /**
         * Used by a Repository implementation to reconstitute a SpendingForecast from persisted data
         * (a JPA entity).
         */
        fun reconstitute(
            forecastId: String,
            accountId: String,
            forecastMonth: String,
            predictedAmount: Long,
            confidence: ForecastConfidence,
            historyMonthsUsed: Int,
            createdAt: LocalDateTime,
        ): SpendingForecast =
            SpendingForecast().apply {
                this.forecastId = forecastId
                this.accountId = accountId
                this.forecastMonth = forecastMonth
                this.predictedAmount = predictedAmount
                this.confidence = confidence
                this.historyMonthsUsed = historyMonthsUsed
                this.createdAt = createdAt
            }
    }
}
