package command

import "context"

// PaymentAccountView holds only the minimal information the Payment BC
// actually needs about an account — unlike Card's AccountView (active status
// only), it also includes the balance needed to judge whether payment is
// possible (account active status + sufficient balance). It is kept as a
// separate Payment-specific type so its name doesn't collide (see the
// explanation in payment_card_adapter.go).
type PaymentAccountView struct {
	AccountID string
	Active    bool
	Balance   int64
	Currency  string
}

// PaymentAccountAdapter is the port (ACL interface) the Payment BC uses to
// synchronously look up an account. It uses the synchronous Adapter pattern
// because whether payment is possible must be verified immediately within
// the current request. The actual debit is not this synchronous query's
// job — the Account BC subscribes to the payment.completed.v1 Integration
// Event and performs it asynchronously (cross-domain.md's "synchronous =
// query, asynchronous Integration Event = state change" principle). The
// implementation lives in infrastructure/acl.
type PaymentAccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*PaymentAccountView, error)
}
