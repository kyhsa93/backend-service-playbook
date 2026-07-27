package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// GetSpendingAnalysisQuery looks up an account's precomputed monthly
// spending analysis. AnalysisMonth is in "2006-01" form.
type GetSpendingAnalysisQuery struct {
	AccountID     string
	RequesterID   string
	AnalysisMonth string
}

// GetSpendingAnalysisHandler verifies account ownership (reusing
// account.FindOne, the same helper GetAccountHandler uses — Account, unlike
// Payment/Refund, has an ownerId column directly, so ownership is a single
// lookup, not a two-hop verification), then looks up the analysis row.
// Unlike nestjs's SpendingAnalysisQuery (a dedicated read-side abstraction
// that re-verifies ownership itself via its own SQL join against
// AccountEntity), Go reuses the existing account.Query port for that first
// check instead of introducing a redundant one, since account.FindOne
// already does exactly this.
type GetSpendingAnalysisHandler struct {
	accounts account.Query
	analyses account.SpendingAnalysisQuery
}

func NewGetSpendingAnalysisHandler(accounts account.Query, analyses account.SpendingAnalysisQuery) *GetSpendingAnalysisHandler {
	return &GetSpendingAnalysisHandler{accounts: accounts, analyses: analyses}
}

func (h *GetSpendingAnalysisHandler) Handle(ctx context.Context, q GetSpendingAnalysisQuery) (*SpendingAnalysisResult, error) {
	if _, err := account.FindOne(ctx, h.accounts, q.AccountID, q.RequesterID); err != nil {
		return nil, fmt.Errorf("get spending analysis: %w", err)
	}

	analysis, err := h.analyses.FindAnalysis(ctx, q.AccountID, q.AnalysisMonth)
	if err != nil {
		return nil, fmt.Errorf("get spending analysis: %w", err)
	}

	return &SpendingAnalysisResult{
		AnalysisMonth:           analysis.AnalysisMonth,
		TotalAmount:             analysis.TotalAmount,
		TransactionCount:        analysis.TransactionCount,
		AverageAmount:           analysis.AverageAmount,
		ChangeFromPreviousMonth: analysis.ChangeFromPreviousMonth,
		Trend:                   string(analysis.Trend),
		CreatedAt:               analysis.CreatedAt,
	}, nil
}
