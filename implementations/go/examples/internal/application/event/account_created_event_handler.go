package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// Notifier는 이벤트 핸들러가 알림 발송에 필요로 하는 최소 포트다.
// 실제 구현은 notification.Service가 맡는다.
type Notifier interface {
	Notify(ctx context.Context, event account.DomainEvent) error
}

// AccountCreatedEventHandler는 outbox에 적재된 AccountCreated 페이로드를 역직렬화해
// 계좌 개설 알림 이메일로 변환한다.
type AccountCreatedEventHandler struct {
	notifier Notifier
}

func NewAccountCreatedEventHandler(notifier Notifier) *AccountCreatedEventHandler {
	return &AccountCreatedEventHandler{notifier: notifier}
}

// Handle은 outbox.Handler 시그니처를 만족한다 — Relay가 event_type="AccountCreated" 행을
// 만날 때마다 호출한다.
func (h *AccountCreatedEventHandler) Handle(ctx context.Context, payload []byte) error {
	var evt account.AccountCreated
	if err := json.Unmarshal(payload, &evt); err != nil {
		return fmt.Errorf("unmarshal AccountCreated: %w", err)
	}
	return h.notifier.Notify(ctx, evt)
}
