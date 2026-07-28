package account

import (
	"context"
	"time"
)

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

// FindTransactionsQuery narrows a FindTransactions lookup to a specific
// account, optionally further narrowed by transaction type and/or a date
// range. Used both by the plain transaction-history listing
// (GetTransactionsHandler) and by AskTransactionHistoryHandler, whose
// Type/FromDate/ToDate fields there come from a Technical Service's (LLM)
// interpretation of a free-text question (see
// docs/architecture/domain-service.md's structured-data RAG example).
//
// Deliberately has no OwnerID field: by the time either Handler builds one,
// FindOne has already verified AccountID belongs to the authenticated
// requester, so ownership is never re-derived from this filter — this
// keeps the "the LLM may only narrow WHAT is returned, never WHO it
// belongs to" guarantee structural rather than convention-based.
type FindTransactionsQuery struct {
	AccountID string
	Type      TransactionType // empty = no type filter
	FromDate  string          // ISO 8601 date (YYYY-MM-DD), inclusive lower bound; empty = unbounded
	ToDate    string          // ISO 8601 date (YYYY-MM-DD), inclusive upper bound; empty = unbounded
	Page      int
	Take      int
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
	FindTransactions(ctx context.Context, q FindTransactionsQuery) ([]Transaction, int, error)

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

	// SummarizeTransactions aggregates one account's transactions in a date
	// range — used by AnalyzeMonthlySpendingHandler to total up a month's
	// (and the prior month's, for comparison) WITHDRAWAL activity without
	// loading every individual Transaction row into memory.
	SummarizeTransactions(ctx context.Context, q SummarizeTransactionsQuery) (TransactionSummary, error)

	// FindRecentWithdrawalAmounts is the training data for
	// IsWithdrawalAnomalous — the account's own recent WITHDRAWAL amounts
	// (order doesn't matter, unlike SpendingAnalysisRepository.
	// FindRecentAnalyses, since the Domain Service only computes a
	// mean/stddev over the set). excludeTransactionID is the withdrawal
	// being judged itself — by the time DetectWithdrawalAnomalyEventHandler
	// runs (after the Outbox has delivered MoneyWithdrawn), that
	// transaction is already persisted, so it must be excluded or it would
	// skew its own baseline.
	FindRecentWithdrawalAmounts(ctx context.Context, accountID, excludeTransactionID string, limit int) ([]int64, error)
}

// SummarizeTransactionsQuery narrows a SummarizeTransactions aggregation to
// one account, one or more transaction types, and a [CreatedFrom, CreatedTo)
// date range.
type SummarizeTransactionsQuery struct {
	AccountID   string
	Type        []TransactionType
	CreatedFrom time.Time
	CreatedTo   time.Time
}

// TransactionSummary is the result of SummarizeTransactions — a count and
// total amount, not the individual rows.
type TransactionSummary struct {
	Count       int
	TotalAmount int64
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
