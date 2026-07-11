package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type GetAccountQuery struct {
	AccountID   string
	RequesterID string
}

type GetAccountHandler struct {
	repo account.QueryRepository
}

func NewGetAccountHandler(repo account.QueryRepository) *GetAccountHandler {
	return &GetAccountHandler{repo: repo}
}

func (h *GetAccountHandler) Handle(ctx context.Context, q GetAccountQuery) (*GetAccountResult, error) {
	a, err := h.repo.FindByID(ctx, q.AccountID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get account: %w", err)
	}
	return &GetAccountResult{
		AccountID: a.AccountID,
		OwnerID:   a.OwnerID,
		Email:     a.Email,
		Balance:   MoneyResult{Amount: a.Balance.Amount, Currency: a.Balance.Currency},
		Status:    string(a.Status),
		CreatedAt: a.CreatedAt,
		UpdatedAt: a.UpdatedAt,
	}, nil
}
