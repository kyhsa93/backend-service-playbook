package com.example.accountservice.account.domain

import com.example.accountservice.common.generateId
import java.time.LocalDateTime
import kotlin.math.roundToLong

enum class SpendingTrend { INCREASING, DECREASING, STABLE }

/**
 * A materialized read-model row — the ETL's precomputed answer to "how did this account's spending
 * change this month," produced monthly by [com.example.accountservice.account.application.command.AnalyzeMonthlySpendingService]
 * and served as-is by [com.example.accountservice.account.application.query.GetSpendingAnalysisService].
 * No business invariant lives here beyond the one real "transform" step (turning two raw totals into
 * a %-change and a trend label), so this stays a plain data holder rather than a stateful Aggregate —
 * still following the `private constructor` + `private set` idiom other domain objects use so the
 * harness's `aggregate-no-public-setters` rule applies uniformly, even though nothing here is ever
 * mutated after creation.
 */
class SpendingAnalysis private constructor() {
    var analysisId: String = ""
        private set

    var accountId: String = ""
        private set

    var analysisMonth: String = ""
        private set

    var totalAmount: Long = 0
        private set

    var transactionCount: Long = 0
        private set

    var averageAmount: Long = 0
        private set

    var changeFromPreviousMonth: Long = 0
        private set

    var trend: SpendingTrend = SpendingTrend.STABLE
        private set

    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        private const val TREND_THRESHOLD_PERCENT = 10

        /**
         * [previousTotalAmount] is always a real computed sum (0 when the account had no withdrawals
         * last month, never null) — there's no "unknown baseline" case to special-case, since a
         * brand-new account with no prior-month history genuinely did spend 0 that month.
         */
        fun create(
            accountId: String,
            analysisMonth: String,
            totalAmount: Long,
            transactionCount: Long,
            previousTotalAmount: Long,
        ): SpendingAnalysis {
            val averageAmount = if (transactionCount > 0) (totalAmount.toDouble() / transactionCount).roundToLong() else 0L

            val changeFromPreviousMonth =
                if (previousTotalAmount == 0L) {
                    if (totalAmount == 0L) 0L else 100L
                } else {
                    ((totalAmount - previousTotalAmount).toDouble() / previousTotalAmount * 100).roundToLong()
                }

            val trend =
                when {
                    changeFromPreviousMonth > TREND_THRESHOLD_PERCENT -> SpendingTrend.INCREASING
                    changeFromPreviousMonth < -TREND_THRESHOLD_PERCENT -> SpendingTrend.DECREASING
                    else -> SpendingTrend.STABLE
                }

            return SpendingAnalysis().apply {
                this.analysisId = generateId()
                this.accountId = accountId
                this.analysisMonth = analysisMonth
                this.totalAmount = totalAmount
                this.transactionCount = transactionCount
                this.averageAmount = averageAmount
                this.changeFromPreviousMonth = changeFromPreviousMonth
                this.trend = trend
                this.createdAt = LocalDateTime.now()
            }
        }

        /**
         * Used by a Repository implementation to reconstitute a SpendingAnalysis from persisted data
         * (a JPA entity).
         */
        fun reconstitute(
            analysisId: String,
            accountId: String,
            analysisMonth: String,
            totalAmount: Long,
            transactionCount: Long,
            averageAmount: Long,
            changeFromPreviousMonth: Long,
            trend: SpendingTrend,
            createdAt: LocalDateTime,
        ): SpendingAnalysis =
            SpendingAnalysis().apply {
                this.analysisId = analysisId
                this.accountId = accountId
                this.analysisMonth = analysisMonth
                this.totalAmount = totalAmount
                this.transactionCount = transactionCount
                this.averageAmount = averageAmount
                this.changeFromPreviousMonth = changeFromPreviousMonth
                this.trend = trend
                this.createdAt = createdAt
            }
    }
}
