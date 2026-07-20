package domain

import "context"

type Repository interface {
	FindAll(ctx context.Context) ([]*Account, error)
	SaveAccount(ctx context.Context, a *Account) error
}
