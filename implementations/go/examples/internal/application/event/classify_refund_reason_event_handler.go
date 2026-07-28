package event

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"

	"github.com/example/account-service/internal/domain/payment"
)

// ClassifyRefundReasonEventHandler reacts to RefundRequested (published
// unconditionally by payment.NewRefund, before EvaluateRefundEligibility's
// approve/reject judgment even runs) to classify the refund's free-text
// reason for ops-analytics reporting only — see
// application/query/get_refund_reason_insights_handler.go. Runs off the
// request hot path (RequestRefundHandler never calls an LLM directly), and
// its result is never read back into any eligibility/approval decision.
// Inherently idempotent: a retried delivery just re-runs the same
// find→classify→save cycle, landing on the same (or an equally acceptable)
// category.
type ClassifyRefundReasonEventHandler struct {
	classifier RefundReasonClassifier
	refunds    payment.RefundRepository
}

func NewClassifyRefundReasonEventHandler(classifier RefundReasonClassifier, refunds payment.RefundRepository) *ClassifyRefundReasonEventHandler {
	return &ClassifyRefundReasonEventHandler{classifier: classifier, refunds: refunds}
}

// Handle satisfies the outbox.Handler signature — it is invoked whenever the
// Consumer encounters an event_type="RefundRequested" message.
func (h *ClassifyRefundReasonEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt payment.RefundRequested
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal RefundRequested: %w", err)
	}

	refund, err := payment.FindOneRefund(ctx, h.refunds, evt.RefundID)
	if err != nil {
		if errors.Is(err, payment.ErrNotFound) {
			// A stale/duplicate delivery referencing a since-deleted refund
			// degrades to a no-op instead of blocking retry forever — the
			// same posture as CategorizeTransactionEventHandler's
			// nil-transaction check.
			return nil
		}
		return fmt.Errorf("find refund: %w", err)
	}

	category := h.classifier.Classify(ctx, evt.Reason)
	refund.CategorizeReason(category)

	if err := h.refunds.SaveRefund(ctx, refund); err != nil {
		return fmt.Errorf("save refund: %w", err)
	}
	slog.InfoContext(ctx, "refund reason classified", "refund_id", evt.RefundID, "category", category)
	return nil
}
