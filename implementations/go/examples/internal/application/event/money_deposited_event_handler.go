package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// MoneyDepositedEventHandler deserializes the MoneyDeposited payload
// persisted in the outbox and translates it into a deposit notification email.
type MoneyDepositedEventHandler struct {
	notifier Notifier
}

func NewMoneyDepositedEventHandler(notifier Notifier) *MoneyDepositedEventHandler {
	return &MoneyDepositedEventHandler{notifier: notifier}
}

func (h *MoneyDepositedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.MoneyDeposited
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal MoneyDeposited: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
