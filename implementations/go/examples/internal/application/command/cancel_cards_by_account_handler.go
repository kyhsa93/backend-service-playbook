package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/card"
)

type CancelCardsByAccountCommand struct {
	AccountID string
}

// CancelCardsByAccountHandler is a use case reacting to the Account BC's
// account.closed.v1 Integration Event. It only cancels cards that are not
// yet cancelled (ACTIVE/SUSPENDED), so it is idempotent under redelivery.
type CancelCardsByAccountHandler struct {
	repo card.Repository
}

func NewCancelCardsByAccountHandler(repo card.Repository) *CancelCardsByAccountHandler {
	return &CancelCardsByAccountHandler{repo: repo}
}

func (h *CancelCardsByAccountHandler) Handle(ctx context.Context, cmd CancelCardsByAccountCommand) error {
	cards, _, err := h.repo.FindCards(ctx, card.FindQuery{
		AccountID: cmd.AccountID,
		Status:    []card.Status{card.StatusActive, card.StatusSuspended},
		Take:      1000,
		Page:      0,
	})
	if err != nil {
		return fmt.Errorf("cancel cards by account: %w", err)
	}

	for _, c := range cards {
		if err := c.Cancel(); err != nil {
			return err
		}
		if err := h.repo.SaveCard(ctx, c); err != nil {
			return err
		}
	}
	return nil
}
