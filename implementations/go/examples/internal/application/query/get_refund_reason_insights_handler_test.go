package query_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/example/account-service/internal/application/query"
	"github.com/example/account-service/internal/domain/payment"
)

// stubRefundReasonInsightsQuery records the filter it was called with, so
// tests can assert GetRefundReasonInsightsHandler passes fromDate/toDate
// through untouched, and returns whatever counts the infrastructure layer
// found.
type stubRefundReasonInsightsQuery struct {
	counts    []payment.RefundReasonInsightCount
	err       error
	lastQuery payment.RefundReasonInsightFilter
}

func (s *stubRefundReasonInsightsQuery) FindReasonInsights(ctx context.Context, filter payment.RefundReasonInsightFilter) ([]payment.RefundReasonInsightCount, error) {
	s.lastQuery = filter
	if s.err != nil {
		return nil, s.err
	}
	return s.counts, nil
}

func TestGetRefundReasonInsightsHandler_Handle_SumsCountsIntoTotalClassified(t *testing.T) {
	insights := &stubRefundReasonInsightsQuery{counts: []payment.RefundReasonInsightCount{
		{Category: payment.RefundReasonCategoryDefectiveProduct, Count: 3},
		{Category: payment.RefundReasonCategoryChangedMind, Count: 2},
	}}
	handler := query.NewGetRefundReasonInsightsHandler(insights)

	result, err := handler.Handle(context.Background(), query.GetRefundReasonInsightsQuery{})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if result.TotalClassified != 5 {
		t.Fatalf("TotalClassified = %d, want 5", result.TotalClassified)
	}
	if len(result.Counts) != 2 {
		t.Fatalf("want 2 category rows, got %d", len(result.Counts))
	}
}

func TestGetRefundReasonInsightsHandler_Handle_PassesDateRangeThrough(t *testing.T) {
	insights := &stubRefundReasonInsightsQuery{}
	handler := query.NewGetRefundReasonInsightsHandler(insights)

	from, to := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)
	if _, err := handler.Handle(context.Background(), query.GetRefundReasonInsightsQuery{FromDate: from, ToDate: to}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if !insights.lastQuery.CreatedFrom.Equal(from) || !insights.lastQuery.CreatedTo.Equal(to) {
		t.Fatalf("filter = %+v, want CreatedFrom=%v CreatedTo=%v", insights.lastQuery, from, to)
	}
}

func TestGetRefundReasonInsightsHandler_Handle_WhenQueryFails_PropagatesError(t *testing.T) {
	insights := &stubRefundReasonInsightsQuery{err: errors.New("db unavailable")}
	handler := query.NewGetRefundReasonInsightsHandler(insights)

	_, err := handler.Handle(context.Background(), query.GetRefundReasonInsightsQuery{})
	if err == nil {
		t.Fatal("want error to propagate")
	}
}
