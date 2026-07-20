package domain

import "context"

type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, error)
	Save(ctx context.Context, a *Account) error
}
