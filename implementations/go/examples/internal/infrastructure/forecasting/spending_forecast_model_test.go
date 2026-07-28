package forecasting_test

import (
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/infrastructure/forecasting"
)

// TestSpendingForecastModelImpl_Predict mirrors the 4 scenarios in nestjs's
// spending-forecast-model-impl.spec.ts.
func TestSpendingForecastModelImpl_Predict(t *testing.T) {
	model := forecasting.NewSpendingForecastModelImpl()

	t.Run("predict_when_history_is_a_perfect_linear_trend_then_extrapolates_exactly_with_high_confidence", func(t *testing.T) {
		prediction := model.Predict([]command.SpendingHistoryPoint{
			{AnalysisMonth: "2026-04", TotalAmount: 10000},
			{AnalysisMonth: "2026-05", TotalAmount: 20000},
			{AnalysisMonth: "2026-06", TotalAmount: 30000},
		})

		if prediction.PredictedAmount != 40000 {
			t.Fatalf("PredictedAmount = %d, want 40000", prediction.PredictedAmount)
		}
		if prediction.Confidence != account.ForecastConfidenceHigh {
			t.Fatalf("Confidence = %v, want HIGH", prediction.Confidence)
		}
	})

	t.Run("predict_when_history_is_perfectly_flat_then_predicts_the_same_amount_with_high_confidence", func(t *testing.T) {
		prediction := model.Predict([]command.SpendingHistoryPoint{
			{AnalysisMonth: "2026-04", TotalAmount: 15000},
			{AnalysisMonth: "2026-05", TotalAmount: 15000},
			{AnalysisMonth: "2026-06", TotalAmount: 15000},
		})

		if prediction.PredictedAmount != 15000 {
			t.Fatalf("PredictedAmount = %d, want 15000", prediction.PredictedAmount)
		}
		if prediction.Confidence != account.ForecastConfidenceHigh {
			t.Fatalf("Confidence = %v, want HIGH", prediction.Confidence)
		}
	})

	t.Run("predict_when_history_is_noisy_and_non-linear_then_reports_lower_confidence", func(t *testing.T) {
		prediction := model.Predict([]command.SpendingHistoryPoint{
			{AnalysisMonth: "2026-01", TotalAmount: 5000},
			{AnalysisMonth: "2026-02", TotalAmount: 40000},
			{AnalysisMonth: "2026-03", TotalAmount: 3000},
			{AnalysisMonth: "2026-04", TotalAmount: 35000},
			{AnalysisMonth: "2026-05", TotalAmount: 4000},
			{AnalysisMonth: "2026-06", TotalAmount: 38000},
		})

		if prediction.Confidence == account.ForecastConfidenceHigh {
			t.Fatalf("Confidence = %v, want not HIGH", prediction.Confidence)
		}
	})

	t.Run("predict_when_the_trend_is_sharply_decreasing_then_floors_the_prediction_at_0_instead_of_going_negative", func(t *testing.T) {
		prediction := model.Predict([]command.SpendingHistoryPoint{
			{AnalysisMonth: "2026-04", TotalAmount: 30000},
			{AnalysisMonth: "2026-05", TotalAmount: 15000},
			{AnalysisMonth: "2026-06", TotalAmount: 1000},
		})

		if prediction.PredictedAmount != 0 {
			t.Fatalf("PredictedAmount = %d, want 0", prediction.PredictedAmount)
		}
	})
}
