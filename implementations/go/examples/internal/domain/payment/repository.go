package payment

import (
	"context"
	"time"
)

type FindQuery struct {
	Page      int
	Take      int
	PaymentID string
	OwnerID   string
	CardID    string
	AccountID string
	Status    []Status
	// CreatedFrom/CreatedTo are a created_at range filter (unbounded if both
	// are zero time.Time). CreatedFrom is an inclusive (>=) bound and
	// CreatedTo is an exclusive (<) bound — used by the card monthly usage
	// statement aggregation (scheduling.md) to filter "payments falling
	// within this period."
	CreatedFrom time.Time
	CreatedTo   time.Time
}

// Query is a Query-only interface that exposes only read-only lookup
// methods (the same CQRS separation idiom as account.Query, card.Query —
// cqrs-pattern.md).
type Query interface {
	FindPayments(ctx context.Context, q FindQuery) ([]*Payment, int, error)
}

// Repository is a Command-only interface that adds a write method (SavePayment) on top of Query's read methods.
type Repository interface {
	Query
	SavePayment(ctx context.Context, p *Payment) error
}

// FindOne is a helper that wraps the repeated single-record lookup pattern
// (call FindPayments with Take: 1, then pull out the first result, or
// ErrNotFound if there is none) (the same idiom as account.FindOne).
func FindOne(ctx context.Context, q Query, paymentID, ownerID string) (*Payment, error) {
	payments, _, err := q.FindPayments(ctx, FindQuery{PaymentID: paymentID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(payments) == 0 {
		return nil, ErrNotFound
	}
	return payments[0], nil
}

// RefundFindQuery — Refund has no ownerId (it references the original
// payment only via PaymentID). Ownership verification is done by the caller
// first looking up the Payment.
type RefundFindQuery struct {
	Page      int
	Take      int
	RefundID  string
	PaymentID string
	Status    []RefundStatus
}

// RefundQuery is Refund's read-only lookup interface.
type RefundQuery interface {
	FindRefunds(ctx context.Context, q RefundFindQuery) ([]*Refund, int, error)
}

// RefundRepository is a Command-only interface that adds a write method (SaveRefund) on top of RefundQuery.
type RefundRepository interface {
	RefundQuery
	SaveRefund(ctx context.Context, r *Refund) error
}

// FindOneRefund is a helper that wraps the repeated single-record lookup
// pattern (call FindRefunds with RefundID+Take:1, then pull out the first
// result, or ErrNotFound if there is none) — the same idiom as FindOne
// above. ClassifyRefundReasonEventHandler uses this instead of a dedicated
// FindRefund(refundID) Repository method, since Refund's read access is
// already fully expressed by FindRefunds' filter-narrowed find<Noun>s form
// (repository-pattern.md).
func FindOneRefund(ctx context.Context, q RefundQuery, refundID string) (*Refund, error) {
	refunds, _, err := q.FindRefunds(ctx, RefundFindQuery{RefundID: refundID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(refunds) == 0 {
		return nil, ErrNotFound
	}
	return refunds[0], nil
}

// RefundReasonInsightFilter narrows GET /refunds/reason-insights to a
// created_at date range — the same inclusive-from/exclusive-to semantics as
// FindQuery.CreatedFrom/CreatedTo above (fromDate is treated as the
// inclusive start of that day, toDate as the exclusive start of the day
// after it, so the filter itself stays UTC-day-boundary agnostic — the
// Interface layer resolves fromDate/toDate strings into these bounds).
type RefundReasonInsightFilter struct {
	CreatedFrom time.Time
	CreatedTo   time.Time
}

// RefundReasonInsightCount is one row of the GET /refunds/reason-insights
// aggregation — how many classified refunds fall into a given category, in
// the requested range.
type RefundReasonInsightCount struct {
	Category RefundReasonCategory
	Count    int
}

// RefundReasonInsightsQuery is a dedicated ops-analytics read model,
// deliberately separate from RefundQuery — it returns aggregated counts, not
// []*Refund, so it doesn't fit RefundQuery's find<Noun>s shape (the same
// precedent as account.SpendingAnalysisQuery being a separate interface from
// account.Query). It is not scoped by OwnerID/PaymentID at all — its whole
// purpose is to surface refund-reason patterns across every refund, not one
// payment's or one user's (this repo has no separate admin-authorization
// boundary, so it's exposed behind the same baseline auth as every other
// endpoint — see the Interface layer for that simplification's rationale).
type RefundReasonInsightsQuery interface {
	FindReasonInsights(ctx context.Context, filter RefundReasonInsightFilter) ([]RefundReasonInsightCount, error)
}
