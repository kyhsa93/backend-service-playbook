package command_test

import (
	"context"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/card"
)

// stubCardRepository is a minimal mock that injects only the behavior needed per test via function fields.
type stubCardRepository struct {
	findAllFn func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error)
	saveFn    func(ctx context.Context, c *card.Card) error
}

func (s *stubCardRepository) FindCards(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
	if s.findAllFn == nil {
		return nil, 0, nil
	}
	return s.findAllFn(ctx, q)
}

func (s *stubCardRepository) SaveCard(ctx context.Context, c *card.Card) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, c)
}

// stubAccountAdapter is a minimal mock that substitutes the command.AccountAdapter port with function fields.
type stubAccountAdapter struct {
	findAccountFn func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error)
}

func (s *stubAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	return s.findAccountFn(ctx, accountID, ownerID)
}
