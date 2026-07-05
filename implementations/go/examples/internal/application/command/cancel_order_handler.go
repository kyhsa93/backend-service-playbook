package command

import (
	"context"
	"fmt"

	"github.com/example/order-service/internal/domain/order"
)

type CancelOrderCommand struct {
	OrderID string
	Reason  string
}

type CancelOrderHandler struct {
	repo order.Repository
}

func NewCancelOrderHandler(repo order.Repository) *CancelOrderHandler {
	return &CancelOrderHandler{repo: repo}
}

func (h *CancelOrderHandler) Handle(ctx context.Context, cmd CancelOrderCommand) error {
	o, err := h.repo.FindByID(ctx, cmd.OrderID)
	if err != nil {
		return fmt.Errorf("cancel order: %w", err)
	}
	if err := o.Cancel(cmd.Reason); err != nil {
		return err
	}
	return h.repo.Save(ctx, o)
}
