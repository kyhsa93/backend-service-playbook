package command

import "context"

// PaymentCardView는 Payment BC가 카드에 대해 실제로 필요로 하는 최소 정보만 담은 값이다.
// Card BC의 Status enum이나 도메인 모델을 그대로 노출하지 않는다(AccountView와 동일한
// 관용구). command.AccountView와 이름이 겹치지 않도록 Payment 전용 타입을 별도로 둔다 —
// application/command 패키지는 도메인별 하위 패키지로 나뉘지 않은 flat 패키지이므로
// (Card의 AccountAdapter/AccountView가 이미 이 패키지에 있다), 같은 대상(Account)을 향한
// 두 번째 Adapter라도 이름이 충돌하지 않게 Payment 접두어를 붙인다.
type PaymentCardView struct {
	CardID    string
	AccountID string
	Active    bool
}

// PaymentCardAdapter는 Payment BC가 카드를 동기 조회하기 위한 포트(ACL 인터페이스)다.
// 결제 시 카드가 존재하고 활성 상태인지, 연결된 accountId가 무엇인지를 현재 요청 안에서
// 즉시 확인해야 하므로 동기 Adapter 패턴을 쓴다(cross-domain.md). Card BC가 이미 Account를
// 이 방식으로 조회하고 있는 것과 동일한 패턴을 Payment가 재사용한다. 구현체는
// infrastructure/acl에 둔다.
type PaymentCardAdapter interface {
	FindCard(ctx context.Context, cardID, ownerID string) (*PaymentCardView, error)
}
