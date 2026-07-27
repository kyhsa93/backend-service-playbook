package com.example.accountservice.account.application.command;

import java.time.LocalDateTime;

/**
 * The payload of the account.analyze-monthly-spending Task. All four dates are computed by {@code
 * SpendingAnalysisScheduler} at enqueue time and carried through as-is — the same reason as
 * account.pay-interest's {@code date}: if the Consumer recomputed "which month" from the actual
 * clock at processing time, a delayed/backlogged run could analyze the wrong month.
 */
public record AnalyzeMonthlySpendingCommand(
        String analysisMonth,
        LocalDateTime monthStart,
        LocalDateTime monthEnd,
        LocalDateTime previousMonthStart,
        LocalDateTime previousMonthEnd) {}
