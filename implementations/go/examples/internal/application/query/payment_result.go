package query

import "time"

type GetPaymentResult struct {
	PaymentID string
	CardID    string
	AccountID string
	OwnerID   string
	Amount    int64
	Status    string
	CreatedAt time.Time
}

type GetPaymentsResult struct {
	Payments []GetPaymentResult
	Count    int
}

type GetRefundResult struct {
	RefundID       string
	PaymentID      string
	Amount         int64
	Reason         string
	Status         string
	DecisionNote   string
	ReasonCategory string
	CreatedAt      time.Time
}

type GetRefundsResult struct {
	Refunds []GetRefundResult
	Count   int
}

// RefundReasonCategoryCountResult is one row of GET /refunds/reason-insights.
type RefundReasonCategoryCountResult struct {
	Category string
	Count    int
}

// RefundReasonInsightsResult is GetRefundReasonInsightsHandler's Result
// type — Counts omits categories with 0 classified refunds in the requested
// range (the same "narrow, not zero-fill" idiom FindRefunds/FindPayments use
// for their own filters).
type RefundReasonInsightsResult struct {
	Counts          []RefundReasonCategoryCountResult
	TotalClassified int
}
