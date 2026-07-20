package domain

import "context"

type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, error)
	UpdateAccount(ctx context.Context, a *Account) error
}
