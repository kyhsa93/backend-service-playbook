package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// InterestPaidEventHandler deserializes the InterestPaid payload persisted
// in the outbox and translates it into an interest-payment notification
// email. It plays the same role as MoneyDepositedEventHandler in that it
// consumes the Domain Event raised by Account.ApplyInterest (the daily
// interest-payment batch driven by the Task Queue) — the only difference is
// that the trigger is a system batch rather than a user command.
type InterestPaidEventHandler struct {
	notifier Notifier
}

func NewInterestPaidEventHandler(notifier Notifier) *InterestPaidEventHandler {
	return &InterestPaidEventHandler{notifier: notifier}
}

func (h *InterestPaidEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.InterestPaid
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal InterestPaid: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
