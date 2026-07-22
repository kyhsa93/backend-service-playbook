package query

import (
	"context"
	"fmt"

	"github.com/example/account-service/internal/domain/card"
)

type GetCardQuery struct {
	CardID      string
	RequesterID string
}

// GetCardHandler depends only on the read-only card.Query (it does not
// reference the write interface — cqrs-pattern.md). It matches the
// requester (RequesterID) as the owner, so another person's card cannot be
// looked up.
type GetCardHandler struct {
	cards card.Query
}

func NewGetCardHandler(cards card.Query) *GetCardHandler {
	return &GetCardHandler{cards: cards}
}

func (h *GetCardHandler) Handle(ctx context.Context, q GetCardQuery) (*GetCardResult, error) {
	c, err := card.FindOne(ctx, h.cards, q.CardID, q.RequesterID)
	if err != nil {
		return nil, fmt.Errorf("get card: %w", err)
	}
	return &GetCardResult{
		CardID:    c.CardID,
		AccountID: c.AccountID,
		OwnerID:   c.OwnerID,
		Brand:     c.Brand,
		Status:    string(c.Status),
		CreatedAt: c.CreatedAt,
	}, nil
}
