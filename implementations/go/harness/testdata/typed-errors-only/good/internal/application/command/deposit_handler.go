package command

import (
	"context"
	"fmt"
)

type Repository interface {
	Save(ctx context.Context, id string) error
}

func Deposit(ctx context.Context, repo Repository, id string) error {
	if err := repo.Save(ctx, id); err != nil {
		return fmt.Errorf("deposit: %w", err)
	}
	return nil
}
