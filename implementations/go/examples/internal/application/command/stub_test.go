package command_test

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// stubRepository는 테스트별로 필요한 동작만 함수 필드로 주입받는 최소 mock이다.
// findByIDFn은 account.FindOne이 감싸는 단건 조회 시나리오만 흉내내면 충분하므로,
// FindAccounts 구현이 이를 단건 결과([]*account.Account 길이 0 또는 1)로 감싸 반환한다.
type stubRepository struct {
	findByIDFn func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	saveFn     func(ctx context.Context, a *account.Account) error
}

func (s *stubRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	a, err := s.findByIDFn(ctx, q.AccountID, q.OwnerID)
	if err != nil {
		return nil, 0, err
	}
	if a == nil {
		return nil, 0, nil
	}
	return []*account.Account{a}, 1, nil
}

func (s *stubRepository) Save(ctx context.Context, a *account.Account) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, a)
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
