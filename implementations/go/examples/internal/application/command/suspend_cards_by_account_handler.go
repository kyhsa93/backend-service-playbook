package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/card"
)

type SuspendCardsByAccountCommand struct {
	AccountID string
}

// SuspendCardsByAccountHandler is a use case reacting to the Account BC's
// account.suspended.v1 Integration Event. It is implemented idempotently,
// assuming at-least-once delivery — since it only picks out and suspends
// ACTIVE cards, redelivery of the same event (against an already-suspended
// card) does nothing.
type SuspendCardsByAccountHandler struct {
	repo card.Repository
}

func NewSuspendCardsByAccountHandler(repo card.Repository) *SuspendCardsByAccountHandler {
	return &SuspendCardsByAccountHandler{repo: repo}
}

func (h *SuspendCardsByAccountHandler) Handle(ctx context.Context, cmd SuspendCardsByAccountCommand) error {
	cards, _, err := h.repo.FindCards(ctx, card.FindQuery{
		AccountID: cmd.AccountID,
		Status:    []card.Status{card.StatusActive},
		Take:      1000,
		Page:      0,
	})
	if err != nil {
		return fmt.Errorf("suspend cards by account: %w", err)
	}

	for _, c := range cards {
		if err := c.Suspend(); err != nil {
			return err
		}
		if err := h.repo.SaveCard(ctx, c); err != nil {
			return err
		}
	}
	return nil
}
