package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/account"
)

// AccountSuspendedEventHandler는 outbox에 적재된 AccountSuspended 도메인 이벤트를 처리한다.
// 두 가지 후속 작업을 한다:
//  1. 외부 BC(Card 등)용 Integration Event(account.suspended.v1)를 Outbox에 적재한다.
//  2. 계좌 정지 알림 이메일을 발송한다.
type AccountSuspendedEventHandler struct {
	notifier  Notifier
	publisher IntegrationPublisher
}

func NewAccountSuspendedEventHandler(notifier Notifier, publisher IntegrationPublisher) *AccountSuspendedEventHandler {
	return &AccountSuspendedEventHandler{notifier: notifier, publisher: publisher}
}

func (h *AccountSuspendedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountSuspended
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountSuspended: %w", err)
	}

	// 외부 BC용 Integration Event를 Outbox에 적재한다(별도 row). 이 적재가 실패하면
	// 에러를 반환해 이 도메인 이벤트 row가 미처리로 남아 다음 Drain 때 재시도되게 한다.
	ie := integrationevent.AccountSuspendedV1{AccountID: evt.AccountID, SuspendedAt: evt.SuspendedAt}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish account.suspended.v1: %w", err)
	}

	// 알림은 best-effort다 — 실패해도 에러를 반환하지 않는다. 반환하면 이 row가 재드레인되어
	// 위 Integration Event가 중복 발행되기 때문이다(수신 측 Card가 멱등이라 무해하지만
	// 불필요한 증폭을 피한다). 발송 실패는 로그로만 남긴다.
	if err := h.notifier.Notify(ctx, evt); err != nil {
		slog.ErrorContext(ctx, "account suspended notification failed", "account_id", evt.AccountID, "error", err)
	}
	return nil
}
