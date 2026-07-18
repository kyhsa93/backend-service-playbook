package command

import "context"

// PaymentAccountView는 Payment BC가 계좌에 대해 실제로 필요로 하는 최소 정보만 담은 값이다
// — 결제 가능 여부(계좌 활성 여부 + 잔액 충분 여부) 판단에 필요한 잔액까지 포함한다는
// 점이 Card의 AccountView(활성 여부만)와 다르다. 이름이 겹치지 않도록 Payment 전용
// 타입으로 별도로 둔다(payment_card_adapter.go의 설명 참고).
type PaymentAccountView struct {
	AccountID string
	Active    bool
	Balance   int64
	Currency  string
}

// PaymentAccountAdapter는 Payment BC가 계좌를 동기 조회하기 위한 포트(ACL 인터페이스)다.
// 결제 가능 여부를 현재 요청 안에서 즉시 확인해야 하므로 동기 Adapter 패턴을 쓴다.
// 실제 차감은 이 동기 조회의 몫이 아니다 — payment.completed.v1 Integration Event를
// Account BC가 구독해 비동기로 수행한다(cross-domain.md의 "동기=조회, 비동기 Integration
// Event=상태변경" 원칙). 구현체는 infrastructure/acl에 둔다.
type PaymentAccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*PaymentAccountView, error)
}
