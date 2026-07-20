package command

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

type WithdrawCommand struct {
	AccountID   string
	RequesterID string
	Amount      int64
}

type WithdrawHandler struct {
	repo account.Repository
}

func NewWithdrawHandler(repo account.Repository) *WithdrawHandler {
	return &WithdrawHandler{repo: repo}
}

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *WithdrawHandler) Handle(ctx context.Context, cmd WithdrawCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("withdraw: %w", err)
	}
	// 사용자가 직접 요청한 출금이므로 referenceID는 없다("") — Payment BC 반응
	// (withdraw_by_payment_handler.go)만 상관관계 키를 싣는다.
	tx, err := a.Withdraw(cmd.Amount, "")
	if err != nil {
		return nil, err
	}
	if err := h.repo.SaveAccount(ctx, a); err != nil {
		return nil, err
	}
	return &tx, nil
}
