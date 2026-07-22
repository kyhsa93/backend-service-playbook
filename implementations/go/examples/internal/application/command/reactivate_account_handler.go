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
	repo account.Repository
}

func NewReactivateAccountHandler(repo account.Repository) *ReactivateAccountHandler {
	return &ReactivateAccountHandler{repo: repo}
}

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *ReactivateAccountHandler) Handle(ctx context.Context, cmd ReactivateAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("reactivate account: %w", err)
	}
	if err := a.Reactivate(); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
