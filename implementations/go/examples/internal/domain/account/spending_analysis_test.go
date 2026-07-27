package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

// TestNewSpendingAnalysis mirrors the 6 scenarios in nestjs's
// spending-analysis.spec.ts.
func TestNewSpendingAnalysis(t *testing.T) {
	tests := []struct {
		name                   string
		totalAmount            int64
		transactionCount       int
		previousTotalAmount    int64
		wantChangeFromPrevious int
		wantTrend              account.SpendingTrend
		wantAverageAmount      int64
		checkAverageAmount     bool
	}{
		{
			name:                   "spending_increased_by_more_than_10_percent_then_trend_is_INCREASING",
			totalAmount:            15000,
			transactionCount:       3,
			previousTotalAmount:    10000,
			wantChangeFromPrevious: 50,
			wantTrend:              account.SpendingTrendIncreasing,
			wantAverageAmount:      5000,
			checkAverageAmount:     true,
		},
		{
			name:                   "spending_decreased_by_more_than_10_percent_then_trend_is_DECREASING",
			totalAmount:            5000,
			transactionCount:       1,
			previousTotalAmount:    10000,
			wantChangeFromPrevious: -50,
			wantTrend:              account.SpendingTrendDecreasing,
		},
		{
			name:                   "the_change_is_within_10_percent_then_trend_is_STABLE",
			totalAmount:            10500,
			transactionCount:       2,
			previousTotalAmount:    10000,
			wantChangeFromPrevious: 5,
			wantTrend:              account.SpendingTrendStable,
		},
		{
			name:                   "there_was_no_spending_in_either_month_then_0_percent_change_and_STABLE",
			totalAmount:            0,
			transactionCount:       0,
			previousTotalAmount:    0,
			wantChangeFromPrevious: 0,
			wantTrend:              account.SpendingTrendStable,
			wantAverageAmount:      0,
			checkAverageAmount:     true,
		},
		{
			name:                   "there_was_no_spending_last_month_but_spending_this_month_then_100_percent_change_and_INCREASING",
			totalAmount:            3000,
			transactionCount:       1,
			previousTotalAmount:    0,
			wantChangeFromPrevious: 100,
			wantTrend:              account.SpendingTrendIncreasing,
		},
		{
			name:                   "transactionCount_is_0_then_averageAmount_is_0_rather_than_dividing_by_zero",
			totalAmount:            0,
			transactionCount:       0,
			previousTotalAmount:    5000,
			wantChangeFromPrevious: -100,
			wantTrend:              account.SpendingTrendDecreasing,
			wantAverageAmount:      0,
			checkAverageAmount:     true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			analysis := account.NewSpendingAnalysis("account-1", "2026-07", tt.totalAmount, tt.transactionCount, tt.previousTotalAmount)

			if analysis.ChangeFromPreviousMonth != tt.wantChangeFromPrevious {
				t.Fatalf("ChangeFromPreviousMonth = %d, want %d", analysis.ChangeFromPreviousMonth, tt.wantChangeFromPrevious)
			}
			if analysis.Trend != tt.wantTrend {
				t.Fatalf("Trend = %v, want %v", analysis.Trend, tt.wantTrend)
			}
			if tt.checkAverageAmount && analysis.AverageAmount != tt.wantAverageAmount {
				t.Fatalf("AverageAmount = %d, want %d", analysis.AverageAmount, tt.wantAverageAmount)
			}
			if analysis.AccountID != "account-1" {
				t.Fatalf("AccountID = %q, want account-1", analysis.AccountID)
			}
			if analysis.AnalysisMonth != "2026-07" {
				t.Fatalf("AnalysisMonth = %q, want 2026-07", analysis.AnalysisMonth)
			}
		})
	}
}
