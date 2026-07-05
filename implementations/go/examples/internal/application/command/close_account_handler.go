package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type CloseAccountCommand struct {
	AccountID   string
	RequesterID string
}

type CloseAccountHandler struct {
	repo account.Repository
}

func NewCloseAccountHandler(repo account.Repository) *CloseAccountHandler {
	return &CloseAccountHandler{repo: repo}
}

func (h *CloseAccountHandler) Handle(ctx context.Context, cmd CloseAccountCommand) error {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("close account: %w", err)
	}
	if err := a.Close(); err != nil {
		return err
	}
	return h.repo.Save(ctx, a)
}
