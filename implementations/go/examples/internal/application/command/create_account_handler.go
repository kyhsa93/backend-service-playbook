package command

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

type CreateAccountCommand struct {
	RequesterID string
	Currency    string
}

type CreateAccountHandler struct {
	repo account.Repository
}

func NewCreateAccountHandler(repo account.Repository) *CreateAccountHandler {
	return &CreateAccountHandler{repo: repo}
}

func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	a := account.New(cmd.RequesterID, cmd.Currency)
	if err := h.repo.Save(ctx, a); err != nil {
		return nil, err
	}
	return a, nil
}
