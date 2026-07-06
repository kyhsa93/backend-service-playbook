package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// MoneyWithdrawnEventHandler는 outbox에 적재된 MoneyWithdrawn 페이로드를 역직렬화해
// 출금 알림 이메일로 변환한다.
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
