package query

import (
	"context"

	"github.com/example/order-service/internal/domain/order"
)

type GetOrdersQuery struct {
	Page   int
	Take   int
	UserID string
	Status []string
}

type OrderSummary struct {
	OrderID     string
	Status      string
	TotalAmount int
}

type GetOrdersResult struct {
	Orders     []OrderSummary
	TotalCount int
}

type GetOrdersHandler struct {
	repo order.Repository
}

func NewGetOrdersHandler(repo order.Repository) *GetOrdersHandler {
	return &GetOrdersHandler{repo: repo}
}

func (h *GetOrdersHandler) Handle(ctx context.Context, q GetOrdersQuery) (*GetOrdersResult, error) {
	orders, count, err := h.repo.FindAll(ctx, order.FindQuery{
		Page:   q.Page,
		Take:   q.Take,
		UserID: q.UserID,
		Status: q.Status,
	})
	if err != nil {
		return nil, err
	}
	summaries := make([]OrderSummary, len(orders))
	for i, o := range orders {
		summaries[i] = OrderSummary{
			OrderID:     o.OrderID,
			Status:      string(o.Status),
			TotalAmount: o.TotalAmount(),
		}
	}
	return &GetOrdersResult{Orders: summaries, TotalCount: count}, nil
}
