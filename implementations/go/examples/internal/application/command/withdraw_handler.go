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
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewWithdrawHandler(repo account.Repository, outboxRelay OutboxRelay) *WithdrawHandler {
	return &WithdrawHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *WithdrawHandler) Handle(ctx context.Context, cmd WithdrawCommand) (*account.Transaction, error) {
	a, err := h.repo.FindByID(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("withdraw: %w", err)
	}
	tx, err := a.Withdraw(cmd.Amount)
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
