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

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *CloseAccountHandler) Handle(ctx context.Context, cmd CloseAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("close account: %w", err)
	}
	if err := a.Close(); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
