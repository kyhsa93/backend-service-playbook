package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// Notifier is the minimal port event handlers need for sending
// notifications. The real implementation is handled by notification.Service.
type Notifier interface {
	Notify(ctx context.Context, event account.DomainEvent) error
}

// AccountCreatedEventHandler deserializes the AccountCreated payload
// persisted in the outbox and translates it into an account-opening
// notification email.
type AccountCreatedEventHandler struct {
	notifier Notifier
}

func NewAccountCreatedEventHandler(notifier Notifier) *AccountCreatedEventHandler {
	return &AccountCreatedEventHandler{notifier: notifier}
}

// Handle satisfies the outbox.Handler signature — it is invoked whenever the
// Relay encounters an event_type="AccountCreated" row.
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
