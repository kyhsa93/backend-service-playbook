package domain

import "context"

type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, int, error)
}

type Repository interface {
	Query
	SaveAccount(ctx context.Context, a *Account) error
	DeleteAccount(ctx context.Context, accountID string) error
}
