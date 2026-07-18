package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// PaymentCancelledEventHandler는 outbox에 적재된 PaymentCancelled 도메인 이벤트를 수신해
// payment.cancelled.v1 Integration Event로 변환해 Outbox에 적재한다. Account BC가 이를
// 구독해 보상 크레딧(deposit)을 실행한다 — 이미 차감된 금액을 되돌리는 보상 트랜잭션이다.
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
