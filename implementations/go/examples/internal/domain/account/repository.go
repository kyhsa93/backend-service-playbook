package account

import "context"

type FindQuery struct {
	Page      int
	Take      int
	AccountID string
	OwnerID   string
	Status    []Status
}

type Repository interface {
	FindByID(ctx context.Context, accountID, ownerID string) (*Account, error)
	FindAll(ctx context.Context, q FindQuery) ([]*Account, int, error)
	Save(ctx context.Context, account *Account) error
	FindTransactions(ctx context.Context, accountID string, page, take int) ([]Transaction, int, error)
}
