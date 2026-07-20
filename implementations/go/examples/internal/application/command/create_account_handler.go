package command

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

type CreateAccountCommand struct {
	RequesterID string
	Email       string
	Currency    string
}

type CreateAccountHandler struct {
	repo account.Repository
}

func NewCreateAccountHandler(repo account.Repository) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo}
}

// Handle은 저장 후 곧바로 반환한다 — Outbox → SQS 발행/수신은 독립적으로 주기
// 실행되는 outbox.Poller/outbox.Consumer만의 책임이다(동기 드레인 금지,
// domain-events.md).
func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency)
	if err := h.repo.SaveAccount(ctx, a); err != nil {
		return nil, err
	}
	return a, nil
}
