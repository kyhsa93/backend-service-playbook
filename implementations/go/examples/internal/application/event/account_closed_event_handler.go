package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/account"
)

// AccountClosedEventHandler는 outbox에 적재된 AccountClosed 도메인 이벤트를 처리한다.
// 외부 BC(Card 등)용 Integration Event(account.closed.v1)를 Outbox에 적재하고,
// 계좌 종료 알림 이메일을 발송한다(알림은 best-effort — AccountSuspendedEventHandler 참고).
type AccountClosedEventHandler struct {
	notifier  Notifier
	publisher IntegrationPublisher
}

func NewAccountClosedEventHandler(notifier Notifier, publisher IntegrationPublisher) *AccountClosedEventHandler {
	return &AccountClosedEventHandler{notifier: notifier, publisher: publisher}
}

func (h *AccountClosedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountClosed
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountClosed: %w", err)
	}

	ie := integrationevent.AccountClosedV1{AccountID: evt.AccountID, ClosedAt: evt.ClosedAt}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish account.closed.v1: %w", err)
	}

	if err := h.notifier.Notify(ctx, evt); err != nil {
		slog.ErrorContext(ctx, "account closed notification failed", "account_id", evt.AccountID, "error", err)
	}
	return nil
}
