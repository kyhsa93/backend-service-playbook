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
	repo     account.Repository
	notifier Notifier
}

func NewReactivateAccountHandler(repo account.Repository, notifier Notifier) *ReactivateAccountHandler {
	return &ReactivateAccountHandler{repo: repo, notifier: notifier}
}

func (h *ReactivateAccountHandler) Handle(ctx context.Context, cmd ReactivateAccountCommand) error {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("reactivate account: %w", err)
	}
	if err := a.Reactivate(); err != nil {
		return err
	}
	if err := h.repo.Save(ctx, a); err != nil {
		return err
	}
	notify(ctx, h.notifier, a)
	return nil
}
