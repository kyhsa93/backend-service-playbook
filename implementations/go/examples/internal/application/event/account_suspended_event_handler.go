package event

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"

	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/domain/account"
)

// AccountSuspendedEventHandler processes the AccountSuspended domain event
// persisted in the outbox. It performs two follow-up actions:
//  1. Persists an Integration Event (account.suspended.v1) for external BCs
//     (Card, etc.) to the Outbox.
//  2. Sends an account-suspension notification email.
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

	// Persist the Integration Event for external BCs to the Outbox (a
	// separate row). If this persistence fails, return an error so this
	// domain event row remains unprocessed and is retried on the next Drain.
	ie := integrationevent.AccountSuspendedV1{AccountID: evt.AccountID, SuspendedAt: evt.SuspendedAt}
	if err := h.publisher.Publish(ctx, ie.EventName(), ie); err != nil {
		return fmt.Errorf("publish account.suspended.v1: %w", err)
	}

	// The notification is best-effort — no error is returned even on
	// failure. Returning one would cause this row to be re-drained, leading
	// to a duplicate publish of the Integration Event above (harmless since
	// the receiving Card side is idempotent, but this avoids unnecessary
	// amplification). A delivery failure is only logged.
	if err := h.notifier.Notify(ctx, evt); err != nil {
		slog.ErrorContext(ctx, "account suspended notification failed", "account_id", evt.AccountID, "error", err)
	}
	return nil
}
