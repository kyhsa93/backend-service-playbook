package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCancelledEventHandler receives the PaymentCancelled domain event
// persisted in the outbox, translates it into a payment.cancelled.v1
// Integration Event, and persists it to the Outbox. The Account BC
// subscribes to this and executes a compensating credit (deposit) — a
// compensating transaction that reverses an amount already debited.
type PaymentCancelledEventHandler struct {
	publisher IntegrationPublisher
}

func NewPaymentCancelledEventHandler(publisher IntegrationPublisher) *PaymentCancelledEventHandler {
	return &PaymentCancelledEventHandler{publisher: publisher}
}

func (h *PaymentCancelledEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt payment.PaymentCancelled
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal PaymentCancelled: %w", err)
	}

	ie := integrationevent.PaymentCancelledV1{
		PaymentID:   evt.PaymentID,
		AccountID:   evt.AccountID,
		Amount:      evt.Amount,
		CancelledAt: evt.CancelledAt,
	}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish payment.cancelled.v1: %w", err)
	}
	return nil
}
