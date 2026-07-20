package domain

import "context"

type Repository interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, error)
	Delete(ctx context.Context, accountID string) error
}
