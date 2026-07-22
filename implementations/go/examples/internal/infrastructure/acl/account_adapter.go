// Package acl holds the Anticorruption Layer implementations used when the
// Card BC synchronously calls another Bounded Context (Account). The
// interface (command.AccountAdapter) lives in the calling side's (Card)
// Application layer, and this implementation lives in the calling side's
// Infrastructure and imports the Account domain — the dependency direction
// is "Card Infrastructure → Account domain," and Card's Application/Domain
// know nothing about Account at all (cross-domain.md).
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

// AccountAdapter is an ACL implementation satisfying command.AccountAdapter.
// It calls the read interface (account.Query) exposed by the Account BC
// and translates Account's model/errors into the minimal form Card
// understands (command.AccountView). It never references Account's
// Repository/domain objects directly.
type AccountAdapter struct {
	accounts account.Query
}

func NewAccountAdapter(accounts account.Query) *AccountAdapter {
	return &AccountAdapter{accounts: accounts}
}

var _ command.AccountAdapter = (*AccountAdapter)(nil)

func (a *AccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
	acc, err := account.FindOne(ctx, a.accounts, accountID, ownerID)
	if err != nil {
		// Translate the upstream "account not found" error type into a nil signal instead of leaking it into the Card domain.
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("account adapter find account: %w", err)
	}
	// Translate to just the active bool Card needs, without exposing Account's Status enum.
	return &command.AccountView{
		AccountID: acc.AccountID,
		Active:    acc.Status == account.StatusActive,
		Email:     acc.Email,
	}, nil
}
