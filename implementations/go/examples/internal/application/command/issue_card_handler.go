package command

import (
	"context"

	"github.com/example/account-service/internal/domain/card"
)

type IssueCardCommand struct {
	AccountID   string
	Brand       string
	RequesterID string
}

type IssueCardHandler struct {
	repo     card.Repository
	accounts AccountAdapter
}

func NewIssueCardHandler(repo card.Repository, accounts AccountAdapter) *IssueCardHandler {
	return &IssueCardHandler{repo: repo, accounts: accounts}
}

func (h *IssueCardHandler) Handle(ctx context.Context, cmd IssueCardCommand) (*card.Card, error) {
	// 동기 Adapter(ACL)로 연결 계좌를 조회한다 — 발급 가부 판단에 필요하므로 동기 호출.
	view, err := h.accounts.FindAccount(ctx, cmd.AccountID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}
	if view == nil {
		return nil, card.ErrLinkedAccountNotFound
	}
	if !view.Active {
		return nil, card.ErrIssueRequiresActiveAccount
	}

	c := card.IssueCard(cmd.AccountID, cmd.RequesterID, cmd.Brand)
	if err := h.repo.SaveCard(ctx, c); err != nil {
		return nil, err
	}
	return c, nil
}
