package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type SuspendAccountCommand struct {
	AccountID   string
	RequesterID string
}

type SuspendAccountHandler struct {
	repo account.Repository
}

func NewSuspendAccountHandler(repo account.Repository) *SuspendAccountHandler {
	return &SuspendAccountHandler{repo: repo}
}

func (h *SuspendAccountHandler) Handle(ctx context.Context, cmd SuspendAccountCommand) error {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("suspend account: %w", err)
	}
	if err := a.Suspend(); err != nil {
		return err
	}
	return h.repo.Save(ctx, a)
}
