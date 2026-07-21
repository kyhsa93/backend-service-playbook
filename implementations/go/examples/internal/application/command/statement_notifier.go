package command

import "context"

// StatementNotifier는 SendCardUsageStatementHandler가 카드 사용내역 명세서 이메일을
// 보내기 위해 필요로 하는 최소 포트다. application/event.Notifier(계좌 Domain Event →
// 이메일)와 같은 목적(SES 발송 + 발송 기록)이지만, Card BC의 Application 레이어가
// account.DomainEvent 타입에 의존하지 않도록 원시 타입만으로 시그니처를 구성한다
// (account 패키지를 import하면 cross-BC-application-import 규칙 위반이 된다).
// 구현체는 infrastructure/notification.Service가 기존 SES 발송 경로를 재사용해 맡는다.
type StatementNotifier interface {
	NotifyCardStatement(ctx context.Context, accountID, recipient, cardID, period string, paymentCount int, totalAmount int64) error
}
