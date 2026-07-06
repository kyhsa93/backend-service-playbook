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
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewSuspendAccountHandler(repo account.Repository, outboxRelay OutboxRelay) *SuspendAccountHandler {
	return &SuspendAccountHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *SuspendAccountHandler) Handle(ctx context.Context, cmd SuspendAccountCommand) error {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("suspend account: %w", err)
	}
	if err := a.Suspend(); err != nil {
		return err
	}
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	return h.outboxRelay.ProcessPending(ctx)
}
