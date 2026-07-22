package command

import "context"

// PaymentCardView holds only the minimal information the Payment BC actually
// needs about a card. It does not expose the Card BC's Status enum or domain
// model directly (the same idiom as AccountView). It is kept as a separate
// Payment-specific type so its name doesn't collide with command.AccountView
// — since the application/command package is a flat package not split into
// per-domain subpackages (Card's AccountAdapter/AccountView already live in
// this package), even a second Adapter targeting the same subject (Account)
// gets a Payment prefix so names don't clash.
type PaymentCardView struct {
	CardID    string
	AccountID string
	Active    bool
}

// PaymentCardAdapter is the port (ACL interface) the Payment BC uses to
// synchronously look up a card. Because whether a card exists and is active,
// and what accountId it is linked to, must be verified immediately within
// the current request during payment, it uses the synchronous Adapter
// pattern (cross-domain.md). Payment reuses the same pattern the Card BC
// already uses to look up Account. The implementation lives in
// infrastructure/acl.
type PaymentCardAdapter interface {
	FindCard(ctx context.Context, cardID, ownerID string) (*PaymentCardView, error)
}
