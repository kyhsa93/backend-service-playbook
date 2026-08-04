package account

import (
	"math"
	"time"

	"github.com/example/account-service/internal/common"
)

// SpendingTrend classifies the %-change computed by NewSpendingAnalysis.
type SpendingTrend string

const (
	SpendingTrendIncreasing SpendingTrend = "INCREASING"
	SpendingTrendDecreasing SpendingTrend = "DECREASING"
	SpendingTrendStable     SpendingTrend = "STABLE"
)

// spendingTrendThresholdPercent mirrors nestjs's TREND_THRESHOLD_PERCENT.
const spendingTrendThresholdPercent = 10

// SpendingAnalysis is a materialized read-model row — the ETL's precomputed
// answer to "how did this account's spending change this month," produced
// monthly by AnalyzeMonthlySpendingHandler and served as-is by
// GetSpendingAnalysisHandler. No business invariant lives here beyond the
// one real "transform" step (turning two raw totals into a %-change and a
// trend label), so this stays a plain struct rather than a stateful
// Aggregate — there is no Repository method that loads it back into memory
// to call a domain method on it (unlike Account).
type SpendingAnalysis struct {
	AnalysisID              string
	AccountID               string
	AnalysisMonth           string
	TotalAmount             int64
	TransactionCount        int
	AverageAmount           int64
	ChangeFromPreviousMonth int
	Trend                   SpendingTrend
	CreatedAt               time.Time
}

// NewSpendingAnalysis computes the analysis row for one account/month.
// previousTotalAmount is always a real computed sum (0 when the account had
// no withdrawals last month, never a null/missing value) — there is no
// "unknown baseline" case to special-case, since a brand-new account with no
// prior-month history genuinely did spend 0 that month. Mirrors nestjs's
// SpendingAnalysis.create exactly.
func NewSpendingAnalysis(accountID, analysisMonth string, totalAmount int64, transactionCount int, previousTotalAmount int64) *SpendingAnalysis {
	var averageAmount int64
	if transactionCount > 0 {
		averageAmount = int64(math.Round(float64(totalAmount) / float64(transactionCount)))
	}

	var changeFromPreviousMonth int
	switch {
	case previousTotalAmount == 0 && totalAmount == 0:
		changeFromPreviousMonth = 0
	case previousTotalAmount == 0:
		changeFromPreviousMonth = 100
	default:
		changeFromPreviousMonth = int(math.Round(float64(totalAmount-previousTotalAmount) / float64(previousTotalAmount) * 100))
	}

	trend := SpendingTrendStable
	switch {
	case changeFromPreviousMonth > spendingTrendThresholdPercent:
		trend = SpendingTrendIncreasing
	case changeFromPreviousMonth < -spendingTrendThresholdPercent:
		trend = SpendingTrendDecreasing
	}

	return &SpendingAnalysis{
		AnalysisID:              common.NewID(),
		AccountID:               accountID,
		AnalysisMonth:           analysisMonth,
		TotalAmount:             totalAmount,
		TransactionCount:        transactionCount,
		AverageAmount:           averageAmount,
		ChangeFromPreviousMonth: changeFromPreviousMonth,
		Trend:                   trend,
		CreatedAt:               common.Now(),
	}
}
