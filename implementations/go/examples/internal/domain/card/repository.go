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
// Query Handler는 쓰기 메서드(SaveCard)에 접근할 수 없도록 이 인터페이스에만 의존해야 한다
// (account.Query와 동일한 CQRS 분리 관용구 — cqrs-pattern.md).
//
// 조회는 root의 find<Noun>s 컨벤션에 맞춰 FindCards 단일 메서드로 통일한다 — 단건
// 조회 전용 메서드는 두지 않는다. 호출부는 FindOne(이 패키지가 제공하는 헬퍼)로
// FindCards를 Take: 1로 호출하고 첫 결과를 꺼낸다(account.FindOne과 동일한 관용구).
type Query interface {
	FindCards(ctx context.Context, q FindQuery) ([]*Card, int, error)
}

// Repository는 Query의 읽기 메서드에 쓰기 메서드(SaveCard)를 더한 Command 전용 인터페이스다.
type Repository interface {
	Query
	SaveCard(ctx context.Context, card *Card) error
}

// FindOne은 단건 조회 호출부의 반복되는 패턴(FindCards를 Take: 1로 호출한 뒤 첫 번째
// 결과를 꺼내고, 없으면 ErrNotFound)을 감싼 헬퍼다(account.FindOne과 동일한 관용구).
func FindOne(ctx context.Context, q Query, cardID, ownerID string) (*Card, error) {
	cards, _, err := q.FindCards(ctx, FindQuery{CardID: cardID, OwnerID: ownerID, Take: 1})
	if err != nil {
		return nil, err
	}
	if len(cards) == 0 {
		return nil, ErrNotFound
	}
	return cards[0], nil
}
