package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type DepositCommand struct {
	AccountID   string
	RequesterID string
	Amount      int64
}

type DepositHandler struct {
	repo account.Repository
}

func NewDepositHandler(repo account.Repository) *DepositHandler {
	return &DepositHandler{repo: repo}
}

// Handle saves and returns immediately — publishing to/consuming from SQS via
// the Outbox is solely the responsibility of the independently, periodically
// running outbox.Poller/outbox.Consumer (no synchronous draining,
// domain-events.md).
func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("deposit: %w", err)
	}
	// This is a deposit requested directly by the user, so there is no
	// referenceID (""). Only the Payment BC reaction
	// (deposit_by_payment_handler.go) carries a correlation key.
	tx, err := a.Deposit(cmd.Amount, "")
	if err != nil {
		return nil, err
	}
	if err := h.repo.SaveAccount(ctx, a); err != nil {
		return nil, err
	}
	return &tx, nil
}
