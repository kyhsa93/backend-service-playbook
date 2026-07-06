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
	repo        account.Repository
	outboxRelay OutboxRelay
}

func NewCreateAccountHandler(repo account.Repository, outboxRelay OutboxRelay) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo, outboxRelay: outboxRelay}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency)
	if err := h.repo.Save(ctx, a); err != nil {
		return nil, err
	}
	if err := h.outboxRelay.ProcessPending(ctx); err != nil {
		return nil, err
	}
	return a, nil
}
