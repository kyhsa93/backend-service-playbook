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
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewCloseAccountHandler(repo account.Repository, outboxRelay OutboxRelay) *CloseAccountHandler {
	return &CloseAccountHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *CloseAccountHandler) Handle(ctx context.Context, cmd CloseAccountCommand) error {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("close account: %w", err)
	}
	if err := a.Close(); err != nil {
		return err
	}
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	return h.outboxRelay.ProcessPending(ctx)
}
