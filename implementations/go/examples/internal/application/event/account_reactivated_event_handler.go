package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// AccountReactivatedEventHandler는 outbox에 적재된 AccountReactivated 페이로드를
// 역직렬화해 계좌 재개 알림 이메일로 변환한다.
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
