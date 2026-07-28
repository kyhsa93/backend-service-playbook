package query

import (
	"context"
	"fmt"
	"time"

	"github.com/example/account-service/internal/domain/payment"
)

// GetRefundReasonInsightsQuery narrows the aggregation to a created_at date
// range — FromDate/ToDate are the zero time.Time when the caller did not
// supply that bound.
type GetRefundReasonInsightsQuery struct {
	FromDate time.Time
	ToDate   time.Time
}

// GetRefundReasonInsightsHandler — an ops/analytics read model, not a
// per-owner one — deliberately not scoped by RequesterID/OwnerID at all,
// since its whole purpose is to surface refund-reason patterns across every
// refund, not one user's (see payment.RefundReasonInsightsQuery's doc
// comment for why this repo's lack of a separate admin-authorization
// boundary makes this endpoint's simplification acceptable).
type GetRefundReasonInsightsHandler struct {
	insights payment.RefundReasonInsightsQuery
}

func NewGetRefundReasonInsightsHandler(insights payment.RefundReasonInsightsQuery) *GetRefundReasonInsightsHandler {
	return &GetRefundReasonInsightsHandler{insights: insights}
}

func (h *GetRefundReasonInsightsHandler) Handle(ctx context.Context, q GetRefundReasonInsightsQuery) (*RefundReasonInsightsResult, error) {
	counts, err := h.insights.FindReasonInsights(ctx, payment.RefundReasonInsightFilter{
		CreatedFrom: q.FromDate,
		CreatedTo:   q.ToDate,
	})
	if err != nil {
		return nil, fmt.Errorf("get refund reason insights: %w", err)
	}

	results := make([]RefundReasonCategoryCountResult, len(counts))
	total := 0
	for i, c := range counts {
		results[i] = RefundReasonCategoryCountResult{Category: string(c.Category), Count: c.Count}
		total += c.Count
	}
	return &RefundReasonInsightsResult{Counts: results, TotalClassified: total}, nil
}
