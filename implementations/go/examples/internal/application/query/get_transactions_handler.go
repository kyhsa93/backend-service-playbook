package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type GetTransactionsQuery struct {
	AccountID   string
	RequesterID string
	Page        int
	Take        int
}

type GetTransactionsHandler struct {
	repo account.Repository
}

func NewGetTransactionsHandler(repo account.Repository) *GetTransactionsHandler {
	return &GetTransactionsHandler{repo: repo}
}

func (h *GetTransactionsHandler) Handle(ctx context.Context, q GetTransactionsQuery) (*GetTransactionsResult, error) {
	if _, err := h.repo.FindByID(ctx, q.AccountID, q.RequesterID); err != nil {
		return nil, fmt.Errorf("get transactions: %w", err)
	}

	transactions, count, err := h.repo.FindTransactions(ctx, q.AccountID, q.Page, q.Take)
	if err != nil {
		return nil, err
	}

	summaries := make([]TransactionSummary, len(transactions))
	for i, t := range transactions {
		summaries[i] = TransactionSummary{
			TransactionID: t.TransactionID,
			Type:          string(t.Type),
			Amount:        MoneyResult{Amount: t.Amount.Amount, Currency: t.Amount.Currency},
			CreatedAt:     t.CreatedAt,
		}
	}
	return &GetTransactionsResult{Transactions: summaries, Count: count}, nil
}
