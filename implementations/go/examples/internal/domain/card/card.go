package card

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Card는 발급된 카드를 표현하는 Aggregate Root다. 연결 계좌(AccountID)의 활성 여부는
// Card가 알 수 없다 — 발급 가능 여부(계좌 상태)는 Application 레이어가 AccountAdapter(ACL)로
// 동기 조회해 판단한 뒤 IssueCard 팩토리를 호출한다(cross-domain.md 참고).
type Card struct {
	CardID    string
	AccountID string
	OwnerID   string
	Brand     string
	Status    Status
	CreatedAt time.Time
	// LastStatementSentMonth는 마지막으로 월간 카드 사용내역 명세서를 발송한 기간이다
	// ("2006-01" 형식, 예: "2026-07"). 빈 문자열이면 아직 한 번도 보낸 적이 없다는
	// 뜻이다. Account.LastInterestPaidAt과 동일한 관용구 — 이 필드 하나로 "이번
	// 기간은 이미 보냈는가"를 판단할 수 있어(Level 1 — 본질적 멱등) 같은 기간의 배치
	// Task가 at-least-once로 재실행돼도 자연스러운 no-op이 된다.
	LastStatementSentMonth string
}

// IssueCard는 새 카드를 ACTIVE 상태로 발급한다(nestjs Card.issue()에 대응).
func IssueCard(accountID, ownerID, brand string) *Card {
	return &Card{
		CardID:    common.NewID(),
		AccountID: accountID,
		OwnerID:   ownerID,
		Brand:     brand,
		Status:    StatusActive,
		CreatedAt: time.Now(),
	}
}

// Reconstitute는 저장소에서 읽은 행을 도메인 객체로 되살린다(불변식 검사 없이 그대로 복원).
func Reconstitute(cardID, accountID, ownerID, brand string, status Status, createdAt time.Time, lastStatementSentMonth string) *Card {
	return &Card{
		CardID:                 cardID,
		AccountID:              accountID,
		OwnerID:                ownerID,
		Brand:                  brand,
		Status:                 status,
		CreatedAt:              createdAt,
		LastStatementSentMonth: lastStatementSentMonth,
	}
}

// Suspend는 카드를 정지한다. 이미 해지된 카드는 정지할 수 없고, 이미 정지된 카드를
// 다시 정지하는 것도 무의미하므로 에러다(nestjs Card.suspend()와 동일한 규칙).
func (c *Card) Suspend() error {
	if c.Status == StatusCancelled {
		return ErrCancelledCardCannotBeSuspended
	}
	if c.Status == StatusSuspended {
		return ErrAlreadySuspended
	}
	c.Status = StatusSuspended
	return nil
}

// Cancel은 카드를 해지한다. 이미 해지된 카드를 다시 해지하는 것은 에러다.
// ACTIVE/SUSPENDED 어느 상태에서든 해지할 수 있다(nestjs Card.cancel()과 동일).
func (c *Card) Cancel() error {
	if c.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	c.Status = StatusCancelled
	return nil
}

// MarkStatementSent는 period("2006-01" 형식)의 월간 사용내역 명세서를 발송했다고
// 기록한다. 이미 같은 period로 기록돼 있으면 아무 것도 바꾸지 않고 false를 반환한다
// (Level 1 — 본질적 멱등 no-op) — 호출부(SendCardUsageStatementHandler)는 이 신호로
// "실제로 새로 보낸 건인지"를 판단해 Save 여부를 결정한다. 알림 발송(SES 호출) 자체는
// Card가 모르는 외부 부작용이므로, 호출부가 발송에 성공한 뒤에만 이 메서드를 호출하는
// 순서를 지켜야 한다 — 그래야 발송 실패 시 재시도(at-least-once)가 실제로 다시
// 발송을 시도한다.
func (c *Card) MarkStatementSent(period string) bool {
	if c.LastStatementSentMonth == period {
		return false
	}
	c.LastStatementSentMonth = period
	return true
}
