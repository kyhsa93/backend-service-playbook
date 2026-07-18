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
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewDepositHandler(repo account.Repository, outboxRelay OutboxRelay) *DepositHandler {
	return &DepositHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *DepositHandler) Handle(ctx context.Context, cmd DepositCommand) (*account.Transaction, error) {
	a, err := account.FindOne(ctx, h.repo, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("deposit: %w", err)
	}
	// 사용자가 직접 요청한 입금이므로 referenceID는 없다("") — Payment BC 반응
	// (deposit_by_payment_handler.go)만 상관관계 키를 싣는다.
	tx, err := a.Deposit(cmd.Amount, "")
	if err != nil {
		return nil, err
	}
	if err := h.repo.Save(ctx, a); err != nil {
		return nil, err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {
		return nil, err
	}
	return &tx, nil
}
