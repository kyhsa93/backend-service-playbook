package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type ReactivateAccountCommand struct {
	AccountID   string
	RequesterID string
}

type ReactivateAccountHandler struct {
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewReactivateAccountHandler(repo account.Repository, outboxRelay OutboxRelay) *ReactivateAccountHandler {
	return &ReactivateAccountHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *ReactivateAccountHandler) Handle(ctx context.Context, cmd ReactivateAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("reactivate account: %w", err)
	}
	if err := a.Reactivate(); err != nil {
		return err
	}
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	return h.outboxRelay.ProcessPending(ctx)
}
