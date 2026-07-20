package domain

import "context"

type Query interface {
	FindAccounts(ctx context.Context, q FindQuery) ([]*Account, error)
	CountByOwnerId(ctx context.Context, ownerID string) (int, error)
}
