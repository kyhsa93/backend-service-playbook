package command_test

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// stubRepository는 테스트별로 필요한 동작만 함수 필드로 주입받는 최소 mock이다.
type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindByID(ctx context.Context, accountID, ownerID string) (*account.Account, error) {
	return s.findByIDFn(ctx, accountID, ownerID)
}

func (s *stubRepository) Save(ctx context.Context, a *account.Account) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, a)
}

func (s *stubRepository) FindAll(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	return nil, 0, nil
}

func (s *stubRepository) FindTransactions(
	ctx context.Context, accountID string, page, take int,
) ([]account.Transaction, int, error) {
	return nil, 0, nil
}

type stubOutboxRelay struct{ processed int }

func (s *stubOutboxRelay) ProcessPending(ctx context.Context) error {
	s.processed++
	return nil
}
