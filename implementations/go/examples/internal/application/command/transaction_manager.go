package command

import "context"

// TransactionManager is the minimal port a Command Handler depends on when
// it needs to atomically wrap two or more Repository writes (a Go port of
// the TransactionManager from root docs/architecture/layer-architecture.md —
// like StatementNotifier/AccountAdapter, the interface is declared near
// where it's used, and infrastructure/database.Manager structurally
// satisfies the implementation).
//
// TransferHandler is the first consumer — if the two SaveAccount calls
// (saving the debited account and saving the credited account) aren't
// wrapped in a single commit, a failure mode arises where "the withdrawal
// was applied but the deposit was lost."
type TransactionManager interface {
	RunInTx(ctx context.Context, fn func(ctx context.Context) error) error
}
