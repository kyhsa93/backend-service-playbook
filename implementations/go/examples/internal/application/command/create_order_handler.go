package command

import (
	"context"

	"github.com/example/order-service/internal/domain/order"
)

type CreateOrderCommand struct {
	UserID string
	Items  []order.ItemInput
}

type CreateOrderHandler struct {
	repo order.Repository
}

func NewCreateOrderHandler(repo order.Repository) *CreateOrderHandler {
	return &CreateOrderHandler{repo: repo}
}

func (h *CreateOrderHandler) Handle(ctx context.Context, cmd CreateOrderCommand) error {
	o, err := order.New(cmd.UserID, cmd.Items)
	if err != nil {
		return err
	}
	return h.repo.Save(ctx, o)
}
