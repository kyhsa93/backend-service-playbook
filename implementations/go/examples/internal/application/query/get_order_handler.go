package query

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type GetOrderQuery struct {
	OrderID string
}

type GetOrderResult struct {
	OrderID     string
	Status      string
	TotalAmount int
}

type GetOrderHandler struct {
	repo order.Repository
}

func NewGetOrderHandler(repo order.Repository) *GetOrderHandler {
	return &GetOrderHandler{repo: repo}
}

func (h *GetOrderHandler) Handle(ctx context.Context, q GetOrderQuery) (*GetOrderResult, error) {
	o, err := h.repo.FindByID(ctx, q.OrderID)
	if err != nil {
		return nil, fmt.Errorf("get order: %w", err)
	}
	return &GetOrderResult{
		OrderID:     o.OrderID,
		Status:      string(o.Status),
		TotalAmount: o.TotalAmount(),
	}, nil
}
