package account

import "context"

// TransactionRepository is a separate Repository from Repository/Query above
// — that one only ever inserts Transaction rows in bulk as a side effect of
// SaveAccount (Transaction rows are otherwise insert-only there). This is the
// find→modify-via-domain-method→save<Noun> cycle CategorizeTransactionEventHandler
// needs for the one field (Category) that legitimately gets set after the
// fact (see docs/architecture/repository-pattern.md's "a Repository must not
// have an update method" rule — also enforced by the harness's
// repository-naming rule).
type TransactionRepository interface {
	// FindTransaction returns nil, nil (not an error) when no transaction
	// exists with the given ID — CategorizeTransactionEventHandler treats that as
	// "nothing to categorize" rather than a failure, so a stale/duplicate
	// delivery referencing an already-deleted transaction degrades to a
	// no-op instead of blocking retry forever.
	FindTransaction(ctx context.Context, transactionID string) (*Transaction, error)
	SaveTransaction(ctx context.Context, transaction Transaction) error
}
