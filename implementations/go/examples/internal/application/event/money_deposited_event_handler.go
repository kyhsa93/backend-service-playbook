package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// MoneyDepositedEventHandler는 outbox에 적재된 MoneyDeposited 페이로드를 역직렬화해
// 입금 알림 이메일로 변환한다.
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
