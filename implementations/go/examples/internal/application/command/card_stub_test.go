package command_test

import (
	"context"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/card"
)

// stubCardRepository는 테스트별로 필요한 동작만 함수 필드로 주입받는 최소 mock이다.
type stubCardRepository struct {
	findAllFn  func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error)
	findByIDFn func(ctx context.Context, cardID, ownerID string) (*card.Card, error)
	saveFn     func(ctx context.Context, c *card.Card) error
}

func (s *stubCardRepository) FindByID(ctx context.Context, cardID, ownerID string) (*card.Card, error) {
	if s.findByIDFn == nil {
		return nil, card.ErrNotFound
	}
	return s.findByIDFn(ctx, cardID, ownerID)
}

func (s *stubCardRepository) FindAll(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
	if s.findAllFn == nil {
		return nil, 0, nil
	}
	return s.findAllFn(ctx, q)
}

func (s *stubCardRepository) Save(ctx context.Context, c *card.Card) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, c)
}

// stubAccountAdapter는 command.AccountAdapter 포트를 함수 필드로 대체하는 최소 mock이다.
type stubAccountAdapter struct {
	findAccountFn func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error)
}

func (s *stubAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	return s.findAccountFn(ctx, accountID, ownerID)
}
