package card

import "context"

type FindQuery struct {
	Page      int
	Take      int
	CardID    string
	OwnerID   string
	AccountID string
	Status    []Status
}

// Query는 읽기 전용 조회 메서드만 노출하는 Query 전용 인터페이스다.
// Query Handler는 쓰기 메서드(Save)에 접근할 수 없도록 이 인터페이스에만 의존해야 한다
// (account.Query와 동일한 CQRS 분리 관용구 — cqrs-pattern.md).
type Query interface {
	FindByID(ctx context.Context, cardID, ownerID string) (*Card, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Card, int, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(Save)를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	Save(ctx context.Context, card *Card) error
}
