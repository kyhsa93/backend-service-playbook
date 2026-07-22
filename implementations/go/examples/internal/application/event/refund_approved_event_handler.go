package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// RefundApprovedEventHandler receives the RefundApproved domain event
// persisted in the outbox, translates it into a refund.approved.v1
// Integration Event, and persists it to the Outbox. The Account BC
// subscribes to this and executes a refund credit (deposit).
type RefundApprovedEventHandler struct {
	publisher IntegrationPublisher
}

func NewRefundApprovedEventHandler(publisher IntegrationPublisher) *RefundApprovedEventHandler {
	return &RefundApprovedEventHandler{publisher: publisher}
}

func (h *RefundApprovedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt payment.RefundApproved
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal RefundApproved: %w", err)
	}

	ie := integrationevent.RefundApprovedV1{
		RefundID:   evt.RefundID,
		PaymentID:  evt.PaymentID,
		AccountID:  evt.AccountID,
		Amount:     evt.Amount,
		ApprovedAt: evt.ApprovedAt,
	}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish refund.approved.v1: %w", err)
	}
	return nil
}
