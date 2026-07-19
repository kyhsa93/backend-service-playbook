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

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *CloseAccountHandler) Handle(ctx context.Context, cmd CloseAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("close account: %w", err)
	}
	if err := a.Close(); err != nil {
		return err
	}
	return h.repo.Save(ctx, a)
}
