package account_test

import (
	"testing"

	"github.com/example/account-service/internal/domain/account"
)

// TestNewSpendingForecast verifies the constructor stores the
// already-computed prediction as-is — unlike NewSpendingAnalysis, there is
// no further transform to perform here (see spending_forecast.go).
func TestNewSpendingForecast(t *testing.T) {
	forecast := account.NewSpendingForecast("account-1", "2026-07", 40000, account.ForecastConfidenceHigh, 3)

	if forecast.AccountID != "account-1" {
		t.Fatalf("AccountID = %q, want account-1", forecast.AccountID)
	}
	if forecast.ForecastMonth != "2026-07" {
		t.Fatalf("ForecastMonth = %q, want 2026-07", forecast.ForecastMonth)
	}
	if forecast.PredictedAmount != 40000 {
		t.Fatalf("PredictedAmount = %d, want 40000", forecast.PredictedAmount)
	}
	if forecast.Confidence != account.ForecastConfidenceHigh {
		t.Fatalf("Confidence = %v, want HIGH", forecast.Confidence)
	}
	if forecast.HistoryMonthsUsed != 3 {
		t.Fatalf("HistoryMonthsUsed = %d, want 3", forecast.HistoryMonthsUsed)
	}
	if forecast.ForecastID == "" {
		t.Fatal("want a non-empty ForecastID to be generated")
	}
	if forecast.CreatedAt.IsZero() {
		t.Fatal("want a non-zero CreatedAt to be set")
	}
}
