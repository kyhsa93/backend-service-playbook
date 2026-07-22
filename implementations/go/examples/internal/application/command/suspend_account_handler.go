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

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *SuspendAccountHandler) Handle(ctx context.Context, cmd SuspendAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("suspend account: %w", err)
	}
	if err := a.Suspend(); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
