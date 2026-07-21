package event

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/example/account-service/internal/domain/account"
)

// InterestPaidEventHandler는 outbox에 적재된 InterestPaid 페이로드를 역직렬화해 이자
// 지급 알림 이메일로 변환한다. Account.ApplyInterest(Task Queue가 구동하는 일일 이자
// 지급 배치)가 발생시키는 Domain Event를 소비한다는 점에서, MoneyDepositedEventHandler와
// 동일한 역할이지만 트리거가 사용자 커맨드가 아니라 시스템 배치라는 차이만 있다.
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
