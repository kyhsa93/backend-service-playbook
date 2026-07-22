package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// MoneyWithdrawnEventHandler deserializes the MoneyWithdrawn payload
// persisted in the outbox and translates it into a withdrawal notification email.
type MoneyWithdrawnEventHandler struct {
	notifier Notifier
}

func NewMoneyWithdrawnEventHandler(notifier Notifier) *MoneyWithdrawnEventHandler {
	return &MoneyWithdrawnEventHandler{notifier: notifier}
}

func (h *MoneyWithdrawnEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.MoneyWithdrawn
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal MoneyWithdrawn: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
