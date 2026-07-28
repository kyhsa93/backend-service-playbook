package command_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func threeMonthsHistory() []account.SpendingAnalysis {
	return []account.SpendingAnalysis{
		{AccountID: "account-1", AnalysisMonth: "2026-04", TotalAmount: 10000, TransactionCount: 1, AverageAmount: 10000, ChangeFromPreviousMonth: 100, Trend: account.SpendingTrendIncreasing},
		{AccountID: "account-1", AnalysisMonth: "2026-05", TotalAmount: 20000, TransactionCount: 1, AverageAmount: 20000, ChangeFromPreviousMonth: 100, Trend: account.SpendingTrendIncreasing},
		{AccountID: "account-1", AnalysisMonth: "2026-06", TotalAmount: 30000, TransactionCount: 1, AverageAmount: 30000, ChangeFromPreviousMonth: 50, Trend: account.SpendingTrendIncreasing},
	}
}

func TestForecastSpendingHandler_Handle_TrainsAndSavesAForecast_WhenAtLeast3MonthsOfHistoryAndNoForecastYet(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")

	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 1, nil
			}
			return []*account.Account{a1}, 1, nil
		},
	}

	var findRecentAnalysesCalls []struct {
		accountID, beforeMonth string
		limit                  int
	}
	analysisRepo := &stubSpendingAnalysisRepository{
		findRecentAnalysesFn: func(ctx context.Context, accountID, beforeMonth string, limit int) ([]account.SpendingAnalysis, error) {
			findRecentAnalysesCalls = append(findRecentAnalysesCalls, struct {
				accountID, beforeMonth string
				limit                  int
			}{accountID, beforeMonth, limit})
			return threeMonthsHistory(), nil
		},
	}

	var saved *account.SpendingForecast
	forecastRepo := &stubSpendingForecastRepository{
		hasForecastFn: func(ctx context.Context, accountID, forecastMonth string) (bool, error) { return false, nil },
		saveForecastFn: func(ctx context.Context, f *account.SpendingForecast) error {
			saved = f
			return nil
		},
	}

	var predictedWith []command.SpendingHistoryPoint
	model := &stubSpendingForecastModel{
		predictFn: func(history []command.SpendingHistoryPoint) command.SpendingForecastPrediction {
			predictedWith = history
			return command.SpendingForecastPrediction{PredictedAmount: 40000, Confidence: account.ForecastConfidenceHigh}
		},
	}

	handler := command.NewForecastSpendingHandler(repo, analysisRepo, forecastRepo, model)

	err := handler.Handle(context.Background(), command.ForecastSpendingCommand{ForecastMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(findRecentAnalysesCalls) != 1 {
		t.Fatalf("want FindRecentAnalyses called once, got %d", len(findRecentAnalysesCalls))
	}
	if findRecentAnalysesCalls[0].accountID != a1.AccountID || findRecentAnalysesCalls[0].beforeMonth != "2026-07" || findRecentAnalysesCalls[0].limit != 6 {
		t.Fatalf("FindRecentAnalyses called with unexpected args: %+v", findRecentAnalysesCalls[0])
	}

	wantPoints := []command.SpendingHistoryPoint{
		{AnalysisMonth: "2026-04", TotalAmount: 10000},
		{AnalysisMonth: "2026-05", TotalAmount: 20000},
		{AnalysisMonth: "2026-06", TotalAmount: 30000},
	}
	if len(predictedWith) != len(wantPoints) {
		t.Fatalf("Predict called with %d points, want %d", len(predictedWith), len(wantPoints))
	}
	for i, p := range wantPoints {
		if predictedWith[i] != p {
			t.Fatalf("Predict point[%d] = %+v, want %+v", i, predictedWith[i], p)
		}
	}

	if saved == nil {
		t.Fatal("want SaveForecast to be called")
	}
	if saved.AccountID != a1.AccountID || saved.ForecastMonth != "2026-07" || saved.PredictedAmount != 40000 ||
		saved.Confidence != account.ForecastConfidenceHigh || saved.HistoryMonthsUsed != 3 {
		t.Fatalf("saved forecast = %+v, unexpected values", saved)
	}
}

func TestForecastSpendingHandler_Handle_SkipsWithoutTraining_WhenFewerThan3MonthsOfHistory(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")

	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 1, nil
			}
			return []*account.Account{a1}, 1, nil
		},
	}
	analysisRepo := &stubSpendingAnalysisRepository{
		findRecentAnalysesFn: func(ctx context.Context, accountID, beforeMonth string, limit int) ([]account.SpendingAnalysis, error) {
			return threeMonthsHistory()[:2], nil // only 2 months
		},
	}
	predictCalled := false
	model := &stubSpendingForecastModel{
		predictFn: func(history []command.SpendingHistoryPoint) command.SpendingForecastPrediction {
			predictCalled = true
			return command.SpendingForecastPrediction{}
		},
	}
	saveCalled := false
	forecastRepo := &stubSpendingForecastRepository{
		hasForecastFn: func(ctx context.Context, accountID, forecastMonth string) (bool, error) { return false, nil },
		saveForecastFn: func(ctx context.Context, f *account.SpendingForecast) error {
			saveCalled = true
			return nil
		},
	}

	handler := command.NewForecastSpendingHandler(repo, analysisRepo, forecastRepo, model)
	err := handler.Handle(context.Background(), command.ForecastSpendingCommand{ForecastMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if predictCalled {
		t.Fatal("want Predict not to be called when there are fewer than 3 months of history")
	}
	if saveCalled {
		t.Fatal("want SaveForecast not to be called when there are fewer than 3 months of history")
	}
}

func TestForecastSpendingHandler_Handle_SkipsAccountsAlreadyForecastedForTheMonth(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")

	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 1, nil
			}
			return []*account.Account{a1}, 1, nil
		},
	}
	findRecentAnalysesCalled := false
	analysisRepo := &stubSpendingAnalysisRepository{
		findRecentAnalysesFn: func(ctx context.Context, accountID, beforeMonth string, limit int) ([]account.SpendingAnalysis, error) {
			findRecentAnalysesCalled = true
			return threeMonthsHistory(), nil
		},
	}
	saveCalled := false
	forecastRepo := &stubSpendingForecastRepository{
		hasForecastFn: func(ctx context.Context, accountID, forecastMonth string) (bool, error) { return true, nil },
		saveForecastFn: func(ctx context.Context, f *account.SpendingForecast) error {
			saveCalled = true
			return nil
		},
	}
	model := &stubSpendingForecastModel{}

	handler := command.NewForecastSpendingHandler(repo, analysisRepo, forecastRepo, model)
	err := handler.Handle(context.Background(), command.ForecastSpendingCommand{ForecastMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if findRecentAnalysesCalled {
		t.Fatal("want FindRecentAnalyses not to be called for an already-forecasted account")
	}
	if saveCalled {
		t.Fatal("want SaveForecast not to be called for an already-forecasted account")
	}
}
