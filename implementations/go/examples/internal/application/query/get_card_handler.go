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

// GetCardHandler는 읽기 전용 card.Query에만 의존한다(쓰기 인터페이스를 참조하지 않는다 —
// cqrs-pattern.md). 요청자(RequesterID)를 소유자로 매칭해 다른 사람의 카드는 조회되지 않는다.
type GetCardHandler struct {
	cards card.Query
}

func NewGetCardHandler(cards card.Query) *GetCardHandler {
	return &GetCardHandler{cards: cards}
}

func (h *GetCardHandler) Handle(ctx context.Context, q GetCardQuery) (*GetCardResult, error) {
	c, err := h.cards.FindByID(ctx, q.CardID, q.RequesterID)
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
