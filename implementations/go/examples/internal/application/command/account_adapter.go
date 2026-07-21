package command

import "context"

// AccountView는 Card BC가 계좌에 대해 실제로 필요로 하는 최소 정보만 담은 값이다.
// Account BC의 Status enum이나 도메인 모델을 그대로 노출하지 않는다 — 상류(Account)
// 모델 변경이 Card로 누수되지 않게 하는 Anticorruption Layer의 산출물이다.
type AccountView struct {
	AccountID string
	Active    bool
	// Email은 카드 사용내역 명세서 발송(SendCardUsageStatementHandler)이 통지 대상
	// 주소를 얻기 위해 필요로 한다 — 카드 발급 시(IssueCardHandler)는 쓰이지 않지만,
	// AccountView는 Card BC가 계좌에 대해 갖는 유일한 읽기 모델이므로 새 필드를
	// 추가하는 편이 같은 목적의 View 타입을 중복 선언하는 것보다 낫다.
	Email string
}

// AccountAdapter는 Card BC가 계좌를 동기 조회하기 위한 포트(ACL 인터페이스)다.
// 카드 발급 시 연결 계좌의 존재·활성 여부를 현재 요청 안에서 즉시 확인해야 하므로
// 동기 Adapter 패턴을 쓴다(cross-domain.md). 구현체는 Card 쪽 infrastructure(acl)에 둔다.
//
// 계좌를 찾지 못하면 (nil, nil)을 반환한다 — 상류의 "계좌 없음" 에러 타입을 Card로
// 누수시키지 않고 nil 신호로 번역하는 것이 구현체(ACL)의 책임이다.
type AccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*AccountView, error)
}
