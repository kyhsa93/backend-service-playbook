package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

// Query is a Query-only interface that exposes only read-only lookup
// methods. Query Handlers must depend only on this interface so they have no
// access to write methods (Save). Because Go interfaces use structural
// typing, any implementation that satisfies Repository automatically
// satisfies Query too, without a separate declaration — there's no need for
// two separate implementations.
//
// Lookups are unified into a single FindAccounts method, following the
// root's find<Noun>s convention — there is no dedicated single-record lookup
// method. Callers use FindOne (a helper provided by this package) to call
// FindAccounts with Take: 1 and pull out the first result.
type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)

	// HasTransactionWithReference is the idempotency check that ensures a
	// Payment BC Integration Event reaction (withdraw-by-payment/
	// deposit-by-payment) doesn't create the same transaction twice even
	// under at-least-once redelivery (Level 2 Ledger — see
	// docs/architecture/domain-events.md). Unlike Card's state-based
	// idempotency (suspending an already-suspended card is harmless),
	// moving money produces a different result each time it's applied, so a
	// separate "has this already been processed" check is required.
	//
	// txType must also be checked — a completed payment (WITHDRAWAL) and its
	// compensating refund credit (DEPOSIT) share the same paymentId as
	// referenceID but are different transactions, so checking referenceID
	// alone would incorrectly judge the compensating credit as "already
	// processed" and skip it.
	HasTransactionWithReference(ctx context.Context, referenceID string, txType TransactionType) (bool, error)
}

// Repository is a Command-only interface that adds a write method
// (SaveAccount) on top of Query's read methods.
type Repository interface {
	Query
	SaveAccount(ctx context.Context, account *Account) error
}

// FindOne is a helper that wraps the repeated single-record lookup pattern
// (call FindAccounts with Take: 1, then pull out the first result, or
// ErrNotFound if there is none). It plays the same role as the
// findAccounts(...).stream().findFirst().orElseThrow(...) idiom in
// java/kotlin-springboot, but since Go has no Stream, it's extracted as a
// free function instead — the Repository/Query interface still has only the
// single FindAccounts lookup method.
func FindOne(ctx context.Context, q Query, accountID, ownerID string) (*Account, error) {
	accounts, _, err := q.FindAccounts(ctx, FindQuery{AccountID: accountID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(accounts) == 0 {
		return nil, ErrNotFound
	}
	return accounts[0], nil
}
