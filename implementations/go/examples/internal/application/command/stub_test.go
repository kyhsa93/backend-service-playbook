package command_test

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// stubRepository is a minimal mock that injects only the behavior needed per
// test via function fields. It's enough for findByIDFn to mimic only the
// single-record lookup scenario wrapped by account.FindOne, so the
// FindAccounts implementation wraps its result as a single-element slice
// ([]*account.Account of length 0 or 1).
type stubRepository struct {
	findByIDFn                    func(ctx context.Context, accountID, ownerID string) (*account.Account, error)
	findAllFn                     func(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error)
	saveFn                        func(ctx context.Context, a *account.Account) error
	hasTransactionWithReferenceFn func(ctx context.Context, referenceID string, txType account.TransactionType) (bool, error)
}

// FindAccounts supports two scenarios: if findAllFn is set (e.g. a batch
// like ApplyDailyInterestHandler that iterates over multiple accounts via a
// Status filter), it delegates to it directly; otherwise it falls back to
// the existing findByIDFn-based single-record lookup mimic (the scenario
// wrapped by FindOne).
func (s *stubRepository) FindAccounts(ctx context.Context, q account.FindQuery) ([]*account.Account, int, error) {
	if s.findAllFn != nil {
		return s.findAllFn(ctx, q)
	}
	a, err := s.findByIDFn(ctx, q.AccountID, q.OwnerID)
	if err != nil {
		return nil, 0, err
	}
	if a == nil {
		return nil, 0, nil
	}
	return []*account.Account{a}, 1, nil
}

func (s *stubRepository) SaveAccount(ctx context.Context, a *account.Account) error {
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

func (s *stubRepository) HasTransactionWithReference(
	ctx context.Context, referenceID string, txType account.TransactionType,
) (bool, error) {
	if s.hasTransactionWithReferenceFn == nil {
		return false, nil
	}
	return s.hasTransactionWithReferenceFn(ctx, referenceID, txType)
}

// stubTransactionManager runs fn as-is without a real DB transaction — it is
// enough for a caller like TransferHandler to verify only "does it call the
// two SaveAccount calls wrapped in a single RunInTx," while the actual
// commit/rollback behavior is database.Manager's job (a separate concern).
type stubTransactionManager struct{}

func (stubTransactionManager) RunInTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(ctx)
}
