package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCompletedEventHandler is an Application EventHandler that receives
// the PaymentCompleted domain event persisted in the outbox, translates it
// into an Integration Event for external BCs (payment.completed.v1), and
// persists it to the Outbox. The Account BC subscribes to this Integration
// Event and performs the actual debit (withdraw).
type PaymentCompletedEventHandler struct {
	publisher IntegrationPublisher
}

func NewPaymentCompletedEventHandler(publisher IntegrationPublisher) *PaymentCompletedEventHandler {
	return &PaymentCompletedEventHandler{publisher: publisher}
}

func (h *PaymentCompletedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt payment.PaymentCompleted
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal PaymentCompleted: %w", err)
	}

	ie := integrationevent.PaymentCompletedV1{
		PaymentID:   evt.PaymentID,
		AccountID:   evt.AccountID,
		Amount:      evt.Amount,
		CompletedAt: evt.CompletedAt,
	}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish payment.completed.v1: %w", err)
	}
	return nil
}
