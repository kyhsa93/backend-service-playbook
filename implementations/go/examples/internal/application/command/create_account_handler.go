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
	repo     account.Repository
	notifier Notifier
}

func NewCreateAccountHandler(repo account.Repository, notifier Notifier) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo, notifier: notifier}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Email, cmd.Currency)
	if err := h.repo.Save(ctx, a); err != nil {
		return nil, err
	}
	notify(ctx, h.notifier, a)
	return a, nil
}
