package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/card"
)

type SuspendCardsByAccountCommand struct {
	AccountID string
}

// SuspendCardsByAccountHandler는 Account BC의 account.suspended.v1 Integration Event에
// 대한 반응 유스케이스다. at-least-once 전달을 전제로 멱등하게 구현한다 — ACTIVE 카드만
// 골라 정지하므로 같은 이벤트가 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다.
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
