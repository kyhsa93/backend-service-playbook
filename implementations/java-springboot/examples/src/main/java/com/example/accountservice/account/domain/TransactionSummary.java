package com.example.accountservice.account.domain;

/**
 * The result of {@link AccountRepository#summarizeTransactions} — a count/total-amount pair for a
 * single accountId/type/date-range window, used by {@code AnalyzeMonthlySpendingService} to build
 * both the current- and previous-month totals a {@link SpendingAnalysis} is computed from. Mirrors
 * the same shape as payment's {@code PaymentUsageSummary}.
 */
public record TransactionSummary(long count, long totalAmount) {}
