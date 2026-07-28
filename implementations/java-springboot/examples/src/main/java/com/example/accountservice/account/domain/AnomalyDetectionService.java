package com.example.accountservice.account.domain;

import java.util.List;

/**
 * Domain Service (see root docs/architecture/domain-service.md) — pure logic, no I/O, no framework
 * annotation. Not a Spring bean; the caller instantiates it directly with {@code new} (see {@link
 * TransferEligibilityService} for the same pattern). The judgment "is this withdrawal unusual for
 * this account" only makes sense against that account's own history, which is more than one
 * Transaction's worth of data, so it can't live on {@link Account} or {@link Transaction}
 * themselves. Trains nothing persisted — like {@code SpendingForecastModelImpl}, it fits fresh (a
 * mean/standard deviation, not a regression) from whatever history the caller passes in, every time
 * it's asked.
 */
public class AnomalyDetectionService {

    // The z-score threshold beyond which a withdrawal is flagged — 3 standard deviations from the
    // account's own historical mean, the conventional statistical outlier cutoff (~0.3% of a
    // normal distribution falls beyond it), tunable if it proves too noisy/quiet in practice.
    private static final double Z_SCORE_THRESHOLD = 3.0;

    // A cold-start guard — with fewer than 5 prior withdrawals, a mean/standard deviation computed
    // from the history is too noisy to judge anything against (the same reasoning as
    // MIN_HISTORY_MONTHS_FOR_FORECAST in ForecastSpendingService).
    private static final int MIN_HISTORY_FOR_DETECTION = 5;

    public boolean isAnomalous(List<Long> historicalAmounts, long amount) {
        if (historicalAmounts.size() < MIN_HISTORY_FOR_DETECTION) {
            return false;
        }

        double mean = historicalAmounts.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance =
                historicalAmounts.stream()
                        .mapToDouble(value -> Math.pow(value - mean, 2))
                        .average()
                        .orElse(0);
        double stdDev = Math.sqrt(variance);

        // A perfectly uniform history (stdDev == 0) has no spread to divide a z-score by — any
        // amount other than that constant is, by definition, the account's first-ever deviation.
        if (stdDev == 0) {
            return amount != mean;
        }

        double zScore = Math.abs(amount - mean) / stdDev;
        return zScore > Z_SCORE_THRESHOLD;
    }
}
