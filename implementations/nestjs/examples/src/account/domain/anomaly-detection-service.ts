// The z-score threshold beyond which a withdrawal is flagged — 3 standard deviations from the
// account's own historical mean, the conventional statistical outlier cutoff (~0.3% of a normal
// distribution falls beyond it), tunable if it proves too noisy/quiet in practice.
const Z_SCORE_THRESHOLD = 3

// A cold-start guard — with fewer than 5 prior withdrawals, a mean/stddev computed from the
// history is too noisy to judge anything against (the same reasoning as
// MIN_HISTORY_MONTHS_FOR_FORECAST in forecast-spending-command-handler.ts).
const MIN_HISTORY_FOR_DETECTION = 5

// A Domain Service (see root docs/architecture/domain-service.md) — pure logic, no I/O, no
// framework dependency. The judgment ("is this withdrawal unusual for this account") only
// makes sense against that account's own history, which is more than one Aggregate's worth of
// data (every past Transaction), so it can't live on Account itself. Trains nothing persisted —
// like SpendingForecastModelImpl, it fits fresh (here: a mean/stddev, not a regression) from
// whatever history the caller passes in, every time it's asked.
export class AnomalyDetectionService {
  public isAnomalous(historicalAmounts: number[], amount: number): boolean {
    if (historicalAmounts.length < MIN_HISTORY_FOR_DETECTION) return false

    const mean = historicalAmounts.reduce((sum, value) => sum + value, 0) / historicalAmounts.length
    const variance = historicalAmounts.reduce((sum, value) => sum + (value - mean) ** 2, 0) / historicalAmounts.length
    const stdDev = Math.sqrt(variance)

    // A perfectly uniform history (stdDev === 0) has no spread to divide a z-score by — any
    // amount other than that constant is, by definition, the account's first-ever deviation.
    if (stdDev === 0) return amount !== mean

    const zScore = Math.abs(amount - mean) / stdDev
    return zScore > Z_SCORE_THRESHOLD
  }
}
