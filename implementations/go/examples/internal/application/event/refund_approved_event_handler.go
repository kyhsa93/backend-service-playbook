package event

import (
	"context"
	"encoding/json"
	"fmt"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/payment"
)

// RefundApprovedEventHandler는 outbox에 적재된 RefundApproved 도메인 이벤트를 수신해
// refund.approved.v1 Integration Event로 변환해 Outbox에 적재한다. Account BC가 이를
// 구독해 환불 크레딧(deposit)을 실행한다.
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
