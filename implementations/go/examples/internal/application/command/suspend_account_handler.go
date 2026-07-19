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

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *SuspendAccountHandler) Handle(ctx context.Context, cmd SuspendAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("suspend account: %w", err)
	}
	if err := a.Suspend(); err != nil {
		return err
	}
	return h.repo.Save(ctx, a)
}
