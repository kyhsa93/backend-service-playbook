package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/account"
)

// AccountClosedEventHandler processes the AccountClosed domain event
// persisted in the outbox. It persists an Integration Event
// (account.closed.v1) for external BCs (Card, etc.) to the Outbox, and
// sends an account-closure notification email (notification is best-effort
// — see AccountSuspendedEventHandler).
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
