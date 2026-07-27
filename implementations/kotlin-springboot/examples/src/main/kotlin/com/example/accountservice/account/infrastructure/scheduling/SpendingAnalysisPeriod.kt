package com.example.accountservice.account.infrastructure.scheduling

import java.time.LocalDateTime

/**
 * `account.analyze-monthly-spending`'s target period — "the previous month," plus the month before
 * that (for the %-change comparison). [SpendingAnalysisScheduler] carries this result through the
 * Task payload as-is — the same reason as `InterestPaymentScheduler`'s `payDate`: recomputing "which
 * month" from the clock at processing time (rather than at enqueue time) could close out the wrong
 * month if processing is delayed by a queue backlog.
 */
data class SpendingAnalysisPeriod(
    val analysisMonth: String,
    val monthStart: LocalDateTime,
    val monthEnd: LocalDateTime,
    val previousMonthStart: LocalDateTime,
    val previousMonthEnd: LocalDateTime,
)

/**
 * Returns "the entire previous month" (before this month's 1st) and the month before that, both by
 * the UTC calendar. [now] must already be normalized to UTC by the caller (see
 * [SpendingAnalysisScheduler]) — this function performs only calendar arithmetic, no timezone
 * conversion of its own, so it stays trivially unit-testable and is reused directly by the e2e test to
 * compute the expected backdate month.
 */
fun computePreviousSpendingAnalysisPeriod(now: LocalDateTime): SpendingAnalysisPeriod {
    val currentMonthFirstDay = now.toLocalDate().withDayOfMonth(1)
    val monthStart = currentMonthFirstDay.minusMonths(1).atStartOfDay()
    val monthEnd = currentMonthFirstDay.atStartOfDay()
    val previousMonthStart = currentMonthFirstDay.minusMonths(2).atStartOfDay()
    val previousMonthEnd = monthStart
    val analysisMonth = "%04d-%02d".format(monthStart.year, monthStart.monthValue)
    return SpendingAnalysisPeriod(analysisMonth, monthStart, monthEnd, previousMonthStart, previousMonthEnd)
}
