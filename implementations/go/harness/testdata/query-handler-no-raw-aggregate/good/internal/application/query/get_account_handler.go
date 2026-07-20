package query

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

type GetAccountResult struct {
	AccountID string
}

type GetAccountHandler struct {
	repo account.Query
}

func (h *GetAccountHandler) Handle(ctx context.Context, q GetAccountQuery) (*GetAccountResult, error) {
	return &GetAccountResult{}, nil
}
