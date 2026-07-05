package command

import (
	"context"

	"github.com/example/account-service/internal/domain/account"
)

// Notifier는 계좌 도메인 이벤트를 알림(이메일 등)으로 전달하는 포트다.
// 실제 구현(notification.Service)은 SES 발송 + 발송 내역 저장을 담당하며,
// 실패하더라도 에러를 반환하지 않는다 — 알림은 커맨드의 성공 여부에 영향을 주면 안 된다.
type Notifier interface {
	Notify(ctx context.Context, event account.DomainEvent)
}

// notify는 저장이 끝난 계좌가 쌓아둔 도메인 이벤트를 모두 알림으로 내보내고 비운다.
func notify(ctx context.Context, notifier Notifier, a *account.Account) {
	for _, event := range a.DomainEvents() {
		notifier.Notify(ctx, event)
	}
	a.ClearEvents()
}
