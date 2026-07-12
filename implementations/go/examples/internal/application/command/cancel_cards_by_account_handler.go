package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/card"
)

type CancelCardsByAccountCommand struct {
	AccountID string
}

// CancelCardsByAccountHandler는 Account BC의 account.closed.v1 Integration Event에 대한
// 반응 유스케이스다. 아직 해지되지 않은 카드(ACTIVE·SUSPENDED)만 해지하므로 재수신에 멱등하다.
type CancelCardsByAccountHandler struct {
	repo card.Repository
}

func NewCancelCardsByAccountHandler(repo card.Repository) *CancelCardsByAccountHandler {
	return &CancelCardsByAccountHandler{repo: repo}
}

func (h *CancelCardsByAccountHandler) Handle(ctx context.Context, cmd CancelCardsByAccountCommand) error {
	cards, _, err := h.repo.FindAll(ctx, card.FindQuery{
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
		if err := h.repo.Save(ctx, c); err != nil {
			return err
		}
	}
	return nil
}
