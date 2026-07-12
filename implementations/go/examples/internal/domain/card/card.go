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
func Reconstitute(cardID, accountID, ownerID, brand string, status Status, createdAt time.Time) *Card {
	return &Card{
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Brand:     brand,
		Status:    status,
		CreatedAt: createdAt,
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
