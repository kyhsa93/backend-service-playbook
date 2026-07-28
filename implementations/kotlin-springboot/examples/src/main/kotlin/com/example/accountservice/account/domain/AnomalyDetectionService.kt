package com.example.accountservice.account.domain

import kotlin.math.abs
import kotlin.math.sqrt

// The z-score threshold beyond which a withdrawal is flagged — 3 standard deviations from the
// account's own historical mean, the conventional statistical outlier cutoff (~0.3% of a normal
// distribution falls beyond it), tunable if it proves too noisy/quiet in practice.
private const val Z_SCORE_THRESHOLD = 3.0

// A cold-start guard — with fewer than 5 prior withdrawals, a mean/stddev computed from the
// history is too noisy to judge anything against (the same reasoning as
// MIN_HISTORY_MONTHS_FOR_FORECAST in ForecastSpendingService).
private const val MIN_HISTORY_FOR_DETECTION = 5

/**
 * A Domain Service (see root docs/architecture/domain-service.md) — a plain class with no
 * framework annotations, not registered in the Spring DI container either; the Application layer
 * instantiates it directly as `AnomalyDetectionService()` when needed, the same pattern as
 * [TransferEligibilityService]. The judgment "is this withdrawal unusual for this account" only
 * makes sense against that account's own history, which is more than one [Transaction]'s worth of
 * data, so it can't live on [Account] or [Transaction] themselves. Trains nothing persisted — like
 * `SpendingForecastModelImpl`, it fits fresh (a mean/standard deviation, not a regression) from
 * whatever history the caller passes in, every time it's asked.
 */
class AnomalyDetectionService {
    fun isAnomalous(
        historicalAmounts: List<Long>,
        amount: Long,
    ): Boolean {
        if (historicalAmounts.size < MIN_HISTORY_FOR_DETECTION) return false

        val mean = historicalAmounts.sum().toDouble() / historicalAmounts.size
        val variance = historicalAmounts.sumOf { (it - mean) * (it - mean) } / historicalAmounts.size
        val stdDev = sqrt(variance)

        // A perfectly uniform history (stdDev == 0) has no spread to divide a z-score by — any
        // amount other than that constant is, by definition, the account's first-ever deviation.
        if (stdDev == 0.0) return amount != mean.toLong()

        val zScore = abs(amount - mean) / stdDev
        return zScore > Z_SCORE_THRESHOLD
    }
}
