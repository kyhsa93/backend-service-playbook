package com.example.accountservice.account.domain;

import com.example.accountservice.common.IdGenerator;
import java.time.LocalDateTime;

/**
 * A materialized read-model row — the ETL's precomputed answer to "how did this account's spending
 * change this month," produced monthly by {@code AnalyzeMonthlySpendingService} and served as-is by
 * {@code GetSpendingAnalysisService}. No business invariant lives here beyond the one real
 * "transform" step (turning two raw totals into a %-change and a trend label), so this stays a
 * plain data holder rather than a stateful Aggregate — the same reasoning as {@code Transaction} (a
 * pure domain object with no ORM knowledge; persistence mapping is handled entirely by
 * infrastructure/persistence/SpendingAnalysisJpaEntity + SpendingAnalysisMapper).
 */
public class SpendingAnalysis {

    private static final int TREND_THRESHOLD_PERCENT = 10;

    private String analysisId;
    private String accountId;
    private String analysisMonth;
    private long totalAmount;
    private long transactionCount;
    private long averageAmount;
    private int changeFromPreviousMonth;
    private SpendingTrend trend;
    private LocalDateTime createdAt;

    private SpendingAnalysis() {}

    /**
     * previousTotalAmount is always a real computed sum (0 when the account had no withdrawals last
     * month, never null) — there's no "unknown baseline" case to special-case, since a brand-new
     * account with no prior-month history genuinely did spend 0 that month.
     */
    public static SpendingAnalysis create(
            String accountId,
            String analysisMonth,
            long totalAmount,
            long transactionCount,
            long previousTotalAmount) {
        long averageAmount =
                transactionCount > 0 ? Math.round((double) totalAmount / transactionCount) : 0;

        int changeFromPreviousMonth;
        if (previousTotalAmount == 0) {
            changeFromPreviousMonth = totalAmount == 0 ? 0 : 100;
        } else {
            changeFromPreviousMonth =
                    (int)
                            Math.round(
                                    ((double) (totalAmount - previousTotalAmount)
                                                    / previousTotalAmount)
                                            * 100);
        }

        SpendingTrend trend = SpendingTrend.STABLE;
        if (changeFromPreviousMonth > TREND_THRESHOLD_PERCENT) {
            trend = SpendingTrend.INCREASING;
        } else if (changeFromPreviousMonth < -TREND_THRESHOLD_PERCENT) {
            trend = SpendingTrend.DECREASING;
        }

        SpendingAnalysis analysis = new SpendingAnalysis();
        analysis.analysisId = IdGenerator.generate();
        analysis.accountId = accountId;
        analysis.analysisMonth = analysisMonth;
        analysis.totalAmount = totalAmount;
        analysis.transactionCount = transactionCount;
        analysis.averageAmount = averageAmount;
        analysis.changeFromPreviousMonth = changeFromPreviousMonth;
        analysis.trend = trend;
        analysis.createdAt = LocalDateTime.now();
        return analysis;
    }

    /**
     * Used by a Repository implementation to reconstitute a SpendingAnalysis from persisted data (a
     * JPA entity, etc.).
     */
    public static SpendingAnalysis reconstitute(
            String analysisId,
            String accountId,
            String analysisMonth,
            long totalAmount,
            long transactionCount,
            long averageAmount,
            int changeFromPreviousMonth,
            SpendingTrend trend,
            LocalDateTime createdAt) {
        SpendingAnalysis analysis = new SpendingAnalysis();
        analysis.analysisId = analysisId;
        analysis.accountId = accountId;
        analysis.analysisMonth = analysisMonth;
        analysis.totalAmount = totalAmount;
        analysis.transactionCount = transactionCount;
        analysis.averageAmount = averageAmount;
        analysis.changeFromPreviousMonth = changeFromPreviousMonth;
        analysis.trend = trend;
        analysis.createdAt = createdAt;
        return analysis;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getAnalysisMonth() {
        return analysisMonth;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public long getTransactionCount() {
        return transactionCount;
    }

    public long getAverageAmount() {
        return averageAmount;
    }

    public int getChangeFromPreviousMonth() {
        return changeFromPreviousMonth;
    }

    public SpendingTrend getTrend() {
        return trend;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
