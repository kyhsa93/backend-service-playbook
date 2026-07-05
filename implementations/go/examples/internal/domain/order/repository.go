package order

import "context"

type FindQuery struct {
	Page   int
	Take   int
	UserID string
	Status []string
}

type Repository interface {
	FindByID(ctx context.Context, orderID string) (*Order, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Order, int, error)
	Save(ctx context.Context, order *Order) error
	Delete(ctx context.Context, orderID string) error
}
