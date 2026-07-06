package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountClosedEventHandler는 outbox에 적재된 AccountClosed 페이로드를 역직렬화해
// 계좌 종료 알림 이메일로 변환한다.
type AccountClosedEventHandler struct {
	notifier Notifier
}

func NewAccountClosedEventHandler(notifier Notifier) *AccountClosedEventHandler {
	return &AccountClosedEventHandler{notifier: notifier}
}

func (h *AccountClosedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountClosed
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountClosed: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
