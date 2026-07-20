package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type ReactivateAccountCommand struct {
	AccountID   string
	RequesterID string
}

type ReactivateAccountHandler struct {
	repo account.Repository
}

func NewReactivateAccountHandler(repo account.Repository) *ReactivateAccountHandler {
	return &ReactivateAccountHandler{repo: repo}
}

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *ReactivateAccountHandler) Handle(ctx context.Context, cmd ReactivateAccountCommand) error {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return fmt.Errorf("reactivate account: %w", err)
	}
	if err := a.Reactivate(); err != nil {
		return err
	}
	return h.repo.SaveAccount(ctx, a)
}
