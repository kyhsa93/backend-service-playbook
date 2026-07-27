package com.example.accountservice.account.infrastructure;

import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * A pure function computing account.analyze-monthly-spending's target period — "the previous
 * month," plus the month before that (for the %-change comparison). {@code
 * SpendingAnalysisScheduler} carries this result through the Task payload as-is — recomputing
 * "which month" from the clock at processing time (rather than at enqueue time) could close out the
 * wrong month if processing is delayed by a queue backlog (the same reasoning as {@code
 * PayInterestCommand}'s {@code date}).
 *
 * <p>Returns "the entire previous month" (before this month's 1st) and the month before that.
 */
public record PreviousSpendingAnalysisPeriod(
        String analysisMonth,
        LocalDateTime monthStart,
        LocalDateTime monthEnd,
        LocalDateTime previousMonthStart,
        LocalDateTime previousMonthEnd) {

    public static PreviousSpendingAnalysisPeriod compute(YearMonth now) {
        YearMonth targetMonth = now.minusMonths(1);
        YearMonth priorMonth = targetMonth.minusMonths(1);

        LocalDateTime monthStart = targetMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = targetMonth.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime previousMonthStart = priorMonth.atDay(1).atStartOfDay();
        LocalDateTime previousMonthEnd = monthStart;

        return new PreviousSpendingAnalysisPeriod(
                targetMonth.toString(), monthStart, monthEnd, previousMonthStart, previousMonthEnd);
    }
}
