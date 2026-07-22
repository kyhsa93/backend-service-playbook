package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountReactivatedEventHandler deserializes the AccountReactivated payload
// persisted in the outbox and translates it into an account-reactivation
// notification email.
type AccountReactivatedEventHandler struct {
	notifier Notifier
}

func NewAccountReactivatedEventHandler(notifier Notifier) *AccountReactivatedEventHandler {
	return &AccountReactivatedEventHandler{notifier: notifier}
}

func (h *AccountReactivatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountReactivated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountReactivated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
