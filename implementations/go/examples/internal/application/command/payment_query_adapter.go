package command

import (
	"context"
	"time"
)

// CardPaymentSummary는 Card BC가 월간 사용내역 명세서 발송에 필요로 하는 최소 정보다
// (건수·합계) — Payment의 도메인 모델(Payment 목록, 상태 등)을 그대로 노출하지 않는다.
type CardPaymentSummary struct {
	Count       int
	TotalAmount int64
}

// PaymentQueryAdapter는 Card BC가 Payment BC를 동기 조회하기 위한 포트(ACL 인터페이스)다.
// SendCardUsageStatementHandler가 기간별 이용내역 요약을 얻는 데만 쓰인다 —
// Card의 Application/Domain 레이어는 payment 패키지를 전혀 import하지 않는다
// (cross-domain-communication.md, PaymentCardAdapter/PaymentAccountAdapter와 동일한
// 패턴을 반대 방향으로 적용). 구현체는 infrastructure/acl에 둔다.
type PaymentQueryAdapter interface {
	SummarizeCardPayments(ctx context.Context, cardID string, from, to time.Time) (CardPaymentSummary, error)
}
