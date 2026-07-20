package domain

import "context"

type Repository interface {
	FindByID(ctx context.Context, id string) (*Account, error)
	SaveAccount(ctx context.Context, a *Account) error
}
