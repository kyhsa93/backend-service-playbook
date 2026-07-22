// These are the Anticorruption Layer implementations used when the Payment
// BC synchronously calls two other Bounded Contexts (Card, Account). The
// interfaces (command.PaymentCardAdapter/command.PaymentAccountAdapter)
// live in the calling side's (Payment) Application layer, and this
// implementation lives in the calling side's Infrastructure and imports
// the Card/Account domains — the dependency direction is "Payment
// Infrastructure → Card/Account domain," and Payment's Application/Domain
// know nothing about Card/Account at all (cross-domain.md, the same
// pattern as account_adapter.go).
package acl

import (
	"context"
	"errors"
	"fmt"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
	"github.com/example/account-service/internal/domain/card"
)

// PaymentCardAdapter is an ACL implementation satisfying
// command.PaymentCardAdapter. It calls the read interface (card.Query)
// exposed by the Card BC and translates Card's model/errors into the
// minimal form Payment understands (command.PaymentCardView).
type PaymentCardAdapter struct {
	cards card.Query
}

func NewPaymentCardAdapter(cards card.Query) *PaymentCardAdapter {
	return &PaymentCardAdapter{cards: cards}
}

var _ command.PaymentCardAdapter = (*PaymentCardAdapter)(nil)

func (a *PaymentCardAdapter) FindCard(ctx context.Context, cardID, ownerID string) (*command.PaymentCardView, error) {
	c, err := card.FindOne(ctx, a.cards, cardID, ownerID)
	if err != nil {
		// Translate the upstream "card not found" error type into a nil signal instead of leaking it into the Payment domain.
		if errors.Is(err, card.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("payment card adapter find card: %w", err)
	}
	return &command.PaymentCardView{
		CardID:    c.CardID,
		AccountID: c.AccountID,
		Active:    c.Status == card.StatusActive,
	}, nil
}

// PaymentAccountAdapter is an ACL implementation satisfying
// command.PaymentAccountAdapter. It calls the read interface (account.Query)
// exposed by the Account BC and translates Account's model/errors into the
// minimal form Payment understands (command.PaymentAccountView).
type PaymentAccountAdapter struct {
	accounts account.Query
}

func NewPaymentAccountAdapter(accounts account.Query) *PaymentAccountAdapter {
	return &PaymentAccountAdapter{accounts: accounts}
}

var _ command.PaymentAccountAdapter = (*PaymentAccountAdapter)(nil)

func (a *PaymentAccountAdapter) FindAccount(ctx context.Context, accountID, ownerID string) (*command.PaymentAccountView, error) {
	acc, err := account.FindOne(ctx, a.accounts, accountID, ownerID)
	if err != nil {
		if errors.Is(err, account.ErrNotFound) {
			return nil, nil
		}
		return nil, fmt.Errorf("payment account adapter find account: %w", err)
	}
	return &command.PaymentAccountView{
		AccountID: acc.AccountID,
		Active:    acc.Status == account.StatusActive,
		Balance:   acc.Balance.Amount,
		Currency:  acc.Balance.Currency,
	}, nil
}
