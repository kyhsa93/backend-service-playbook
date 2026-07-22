package command

import (
	"context"

	"github.com/example/account-service/internal/domain/payment"
)

type RequestRefundCommand struct {
	PaymentID   string
	Amount      int64
	Reason      string
	RequesterID string
}

// RequestRefundHandler requests a refund. A decision that cannot be made
// from either Aggregate alone (comparing the original payment's status
// against the refund amount) is delegated to payment.EvaluateRefundEligibility
// (a Domain Service), which this Handler coordinates after loading both the
// Payment and Refund Aggregates together.
//
// A refund rejection is not an error but a valid domain outcome — even when
// rejected, this Handler does not return an error; it returns the Refund
// saved with REJECTED status as-is. The Interface layer responds to this
// not as an error but as 201 + status:REJECTED.
type RequestRefundHandler struct {
	payments   payment.Repository
	refunds    payment.RefundRepository
	classifier RefundReasonClassifier
}

func NewRequestRefundHandler(
	payments payment.Repository,
	refunds payment.RefundRepository,
	classifier RefundReasonClassifier,
) *RequestRefundHandler {
	return &RequestRefundHandler{payments: payments, refunds: refunds, classifier: classifier}
}

func (h *RequestRefundHandler) Handle(ctx context.Context, cmd RequestRefundCommand) (*payment.Refund, error) {
	p, err := payment.FindOne(ctx, h.payments, cmd.PaymentID, cmd.RequesterID)
	if err != nil {
		return nil, err
	}

	r := payment.NewRefund(p.PaymentID, cmd.Amount, cmd.Reason)

	// classifier is a Technical Service (command.RefundReasonClassifier) — this Handler calls it
	// before delegating to the Domain Service below, and passes its result in as one more plain
	// input alongside the two Aggregates.
	classification := h.classifier.Classify(ctx, cmd.Reason)

	decision := payment.EvaluateRefundEligibility(p, r, classification)
	if decision.Approved {
		if err := r.Approve(p.AccountID, p.OwnerID); err != nil {
			return nil, err
		}
	} else {
		reason := decision.Reason
		if reason == "" {
			reason = "refund request rejected"
		}
		if err := r.Reject(reason); err != nil {
			return nil, err
		}
	}

	// Save and return immediately — RefundApproved -> refund.approved.v1 is
	// published to SQS by outbox.Poller on the next tick, and outbox.Consumer
	// runs the Account BC's reaction (no synchronous draining,
	// domain-events.md). When rejected, there is no Domain Event, so there is
	// nothing to publish.
	if err := h.refunds.SaveRefund(ctx, r); err != nil {
		return nil, err
	}
	return r, nil
}
