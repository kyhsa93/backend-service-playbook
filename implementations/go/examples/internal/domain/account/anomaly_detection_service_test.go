package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

// TestIsWithdrawalAnomalous directly verifies the Domain Service's pure
// z-score judgment — no Account/Repository involvement, the same
// instantiate-and-call-directly style as TestEvaluateTransferEligibility.
func TestIsWithdrawalAnomalous(t *testing.T) {
	tests := []struct {
		name    string
		history []int64
		amount  int64
		want    bool
	}{
		{
			name:    "fewer_than_5_historical_withdrawals_returns_false_regardless_of_amount",
			history: []int64{10000, 10000, 10000, 10000},
			amount:  5000000,
			want:    false,
		},
		{
			name:    "amount_close_to_the_historical_mean_returns_false",
			history: []int64{10000, 12000, 9000, 11000, 10500, 9500},
			amount:  10800,
			want:    false,
		},
		{
			name:    "amount_far_beyond_the_historical_spread_returns_true",
			history: []int64{10000, 12000, 9000, 11000, 10500, 9500},
			amount:  5000000,
			want:    true,
		},
		{
			name:    "perfectly_uniform_history_and_a_matching_amount_returns_false",
			history: []int64{10000, 10000, 10000, 10000, 10000},
			amount:  10000,
			want:    false,
		},
		{
			name:    "perfectly_uniform_history_and_a_differing_amount_returns_true",
			history: []int64{10000, 10000, 10000, 10000, 10000},
			amount:  10001,
			want:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := account.IsWithdrawalAnomalous(tt.history, tt.amount)
			if got != tt.want {
				t.Fatalf("IsWithdrawalAnomalous() = %v, want %v", got, tt.want)
			}
		})
	}
}
