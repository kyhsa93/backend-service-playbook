package command_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestAnalyzeMonthlySpendingHandler_Handle_InvalidAnalysisMonth(t *testing.T) {
	repo := &stubRepository{}
	analysisRepo := &stubSpendingAnalysisRepository{}
	handler := command.NewAnalyzeMonthlySpendingHandler(repo, analysisRepo)

	err := handler.Handle(context.Background(), command.AnalyzeMonthlySpendingCommand{AnalysisMonth: "not-a-month"})

	if !errors.Is(err, account.ErrInvalidAnalysisMonth) {
		t.Fatalf("want ErrInvalidAnalysisMonth, got %v", err)
	}
}

func TestAnalyzeMonthlySpendingHandler_Handle_SummarizesBothMonthsAndSavesTheAnalysis(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")

	var summarizeCalls []account.SummarizeTransactionsQuery
	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 1, nil
			}
			return []*account.Account{a1}, 1, nil
		},
		summarizeTransactionsFn: func(ctx context.Context, q account.SummarizeTransactionsQuery) (account.TransactionSummary, error) {
			summarizeCalls = append(summarizeCalls, q)
			// The first call (current month) is distinguished from the second
			// (previous month) by which boundary matches — mirroring the
			// nestjs spec's two mockResolvedValueOnce calls.
			if len(summarizeCalls) == 1 {
				return account.TransactionSummary{Count: 2, TotalAmount: 15000}, nil // current month
			}
			return account.TransactionSummary{Count: 1, TotalAmount: 10000}, nil // previous month
		},
	}

	var saved *account.SpendingAnalysis
	analysisRepo := &stubSpendingAnalysisRepository{
		hasAnalysisFn: func(ctx context.Context, accountID, analysisMonth string) (bool, error) {
			return false, nil
		},
		saveAnalysisFn: func(ctx context.Context, a *account.SpendingAnalysis) error {
			saved = a
			return nil
		},
	}

	handler := command.NewAnalyzeMonthlySpendingHandler(repo, analysisRepo)

	err := handler.Handle(context.Background(), command.AnalyzeMonthlySpendingCommand{AnalysisMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(summarizeCalls) != 2 {
		t.Fatalf("want SummarizeTransactions called twice (current + previous month), got %d", len(summarizeCalls))
	}
	wantMonthStart, wantMonthEnd := mustParseMonth(t, "2026-07"), mustParseMonth(t, "2026-08")
	wantPrevStart := mustParseMonth(t, "2026-06")
	if !summarizeCalls[0].CreatedFrom.Equal(wantMonthStart) || !summarizeCalls[0].CreatedTo.Equal(wantMonthEnd) {
		t.Fatalf("current month range = [%v, %v), want [%v, %v)", summarizeCalls[0].CreatedFrom, summarizeCalls[0].CreatedTo, wantMonthStart, wantMonthEnd)
	}
	if !summarizeCalls[1].CreatedFrom.Equal(wantPrevStart) || !summarizeCalls[1].CreatedTo.Equal(wantMonthStart) {
		t.Fatalf("previous month range = [%v, %v), want [%v, %v)", summarizeCalls[1].CreatedFrom, summarizeCalls[1].CreatedTo, wantPrevStart, wantMonthStart)
	}
	if len(summarizeCalls[0].Type) != 1 || summarizeCalls[0].Type[0] != account.TransactionTypeWithdrawal {
		t.Fatalf("Type = %v, want [WITHDRAWAL]", summarizeCalls[0].Type)
	}

	if saved == nil {
		t.Fatal("want SaveAnalysis to be called")
	}
	if saved.AccountID != a1.AccountID || saved.AnalysisMonth != "2026-07" || saved.TotalAmount != 15000 ||
		saved.TransactionCount != 2 || saved.Trend != account.SpendingTrendIncreasing {
		t.Fatalf("saved analysis = %+v, unexpected values", saved)
	}
}

func TestAnalyzeMonthlySpendingHandler_Handle_SkipsAlreadyAnalyzedAccounts(t *testing.T) {
	a1 := account.New("owner-1", "a1@example.com", "KRW")

	summarizeCalled := false
	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			if q.Page > 0 {
				return nil, 1, nil
			}
			return []*account.Account{a1}, 1, nil
		},
		summarizeTransactionsFn: func(ctx context.Context, q account.SummarizeTransactionsQuery) (account.TransactionSummary, error) {
			summarizeCalled = true
			return account.TransactionSummary{}, nil
		},
	}
	saveCalled := false
	analysisRepo := &stubSpendingAnalysisRepository{
		hasAnalysisFn: func(ctx context.Context, accountID, analysisMonth string) (bool, error) {
			return true, nil
		},
		saveAnalysisFn: func(ctx context.Context, a *account.SpendingAnalysis) error {
			saveCalled = true
			return nil
		},
	}

	handler := command.NewAnalyzeMonthlySpendingHandler(repo, analysisRepo)
	err := handler.Handle(context.Background(), command.AnalyzeMonthlySpendingCommand{AnalysisMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if summarizeCalled {
		t.Fatal("want SummarizeTransactions not to be called for an already-analyzed account")
	}
	if saveCalled {
		t.Fatal("want SaveAnalysis not to be called for an already-analyzed account")
	}
}

func TestAnalyzeMonthlySpendingHandler_Handle_NoActiveAccounts(t *testing.T) {
	repo := &stubRepository{
		findAllFn: func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
			return nil, 0, nil
		},
	}
	saveCalled := false
	analysisRepo := &stubSpendingAnalysisRepository{
		saveAnalysisFn: func(ctx context.Context, a *account.SpendingAnalysis) error {
			saveCalled = true
			return nil
		},
	}

	handler := command.NewAnalyzeMonthlySpendingHandler(repo, analysisRepo)
	err := handler.Handle(context.Background(), command.AnalyzeMonthlySpendingCommand{AnalysisMonth: "2026-07"})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if saveCalled {
		t.Fatal("want SaveAnalysis not to be called when there are no active accounts")
	}
}

func mustParseMonth(t *testing.T, month string) (parsed time.Time) {
	t.Helper()
	parsed, err := time.Parse("2006-01", month)
	if err != nil {
		t.Fatalf("failed to parse month %q: %v", month, err)
	}
	return parsed
}
