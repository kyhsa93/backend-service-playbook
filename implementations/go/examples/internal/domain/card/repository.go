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

// Query is a Query-only interface that exposes only read-only lookup
// methods. Query Handlers must depend only on this interface so they have
// no access to write methods (SaveCard) (the same CQRS separation idiom as
// account.Query — cqrs-pattern.md).
//
// Lookups are unified into a single FindCards method, following the root's
// find<Noun>s convention — there is no dedicated single-record lookup
// method. Callers use FindOne (a helper provided by this package) to call
// FindCards with Take: 1 and pull out the first result (the same idiom as
// account.FindOne).
type Query interface {
	FindCards(ctx context.Context, q FindQuery) ([]*Card, int, error)
}

// Repository is a Command-only interface that adds a write method (SaveCard) on top of Query's read methods.
type Repository interface {
	Query
	SaveCard(ctx context.Context, card *Card) error
}

// FindOne is a helper that wraps the repeated single-record lookup pattern
// (call FindCards with Take: 1, then pull out the first result, or
// ErrNotFound if there is none) (the same idiom as account.FindOne).
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
