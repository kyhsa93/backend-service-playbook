package account

import "math"

// zScoreThreshold is the z-score beyond which a withdrawal is flagged — 3
// standard deviations from the account's own historical mean, the
// conventional statistical outlier cutoff (~0.3% of a normal distribution
// falls beyond it), tunable if it proves too noisy/quiet in practice.
const zScoreThreshold = 3

// minHistoryForAnomalyDetection is a cold-start guard — with fewer than 5
// prior withdrawals, a mean/stddev computed from the history is too noisy
// to judge anything against (the same reasoning as
// minHistoryMonthsForForecast in command.ForecastSpendingHandler).
const minHistoryForAnomalyDetection = 5

// IsWithdrawalAnomalous is "pure domain logic that coordinates multiple
// Aggregates" per the root docs/architecture/domain-service.md — expressed
// as a plain package function, the same "a free function is more idiomatic
// than a stateless struct + method" reasoning already established by
// EvaluateTransferEligibility/EvaluateRefundEligibility (Go has no DI
// container, and this judgment holds no state). The judgment "is this
// withdrawal unusual for this account" only makes sense against that
// account's own history — every past Transaction, more data than a single
// Account instance holds — so it can't live as a method on Account itself.
//
// It computes nothing persisted: like command.SpendingForecastModel, it
// fits fresh (here: a mean/stddev, not a regression) from whatever history
// the caller passes in, every time it's asked.
func IsWithdrawalAnomalous(historicalAmounts []int64, amount int64) bool {
	if len(historicalAmounts) < minHistoryForAnomalyDetection {
		return false
	}

	var sum int64
	for _, v := range historicalAmounts {
		sum += v
	}
	mean := float64(sum) / float64(len(historicalAmounts))

	var varianceSum float64
	for _, v := range historicalAmounts {
		diff := float64(v) - mean
		varianceSum += diff * diff
	}
	stdDev := math.Sqrt(varianceSum / float64(len(historicalAmounts)))

	// A perfectly uniform history (stdDev == 0) has no spread to divide a
	// z-score by — any amount other than that constant is, by definition,
	// the account's first-ever deviation.
	if stdDev == 0 {
		return float64(amount) != mean
	}

	zScore := math.Abs(float64(amount)-mean) / stdDev
	return zScore > zScoreThreshold
}
