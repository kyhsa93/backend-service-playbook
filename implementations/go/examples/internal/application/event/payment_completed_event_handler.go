package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCompletedEventHandler는 outbox에 적재된 PaymentCompleted 도메인 이벤트를 수신해
// 외부 BC용 Integration Event(payment.completed.v1)로 변환해 Outbox에 적재하는
// Application EventHandler다. Account BC가 이 Integration Event를 구독해 실제 차감
// (withdraw)을 수행한다.
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
