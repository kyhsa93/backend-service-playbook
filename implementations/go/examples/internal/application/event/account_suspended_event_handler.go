package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountSuspendedEventHandler는 outbox에 적재된 AccountSuspended 페이로드를 역직렬화해
// 계좌 정지 알림 이메일로 변환한다.
type AccountSuspendedEventHandler struct {
	notifier Notifier
}

func NewAccountSuspendedEventHandler(notifier Notifier) *AccountSuspendedEventHandler {
	return &AccountSuspendedEventHandler{notifier: notifier}
}

func (h *AccountSuspendedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountSuspended
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountSuspended: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
