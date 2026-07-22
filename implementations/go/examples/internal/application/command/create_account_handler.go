package command

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

type CreateAccountCommand struct {
	RequesterID string
	Email       string
	Currency    string
}

type CreateAccountHandler struct {
	repo account.Repository
}

func NewCreateAccountHandler(repo account.Repository) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo}
}

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency)
	if err := h.repo.SaveAccount(ctx, a); err != nil {
		return nil, err
	}
	return a, nil
}
