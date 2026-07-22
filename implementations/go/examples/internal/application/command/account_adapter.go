package command

import "context"

// AccountView holds only the minimal information the Card BC actually needs
// about an account. It does not expose the Account BC's Status enum or
// domain model directly — it is the output of an Anticorruption Layer that
// keeps upstream (Account) model changes from leaking into Card.
type AccountView struct {
	AccountID string
	Active    bool
	// Email is needed by card usage statement delivery (SendCardUsageStatementHandler)
	// to obtain the notification address — it is unused during card issuance
	// (IssueCardHandler), but since AccountView is the Card BC's only read model
	// for accounts, adding a new field here is preferable to declaring a
	// duplicate View type for the same purpose.
	Email string
}

// AccountAdapter is the port (ACL interface) the Card BC uses to synchronously
// look up an account. Because card issuance must immediately verify the
// linked account's existence/active status within the current request, it
// uses the synchronous Adapter pattern (cross-domain.md). The implementation
// lives in Card's infrastructure (acl).
//
// It returns (nil, nil) when the account is not found — translating the
// upstream "account not found" error type into a nil signal instead of
// leaking it into Card is the implementation's (ACL's) responsibility.
type AccountAdapter interface {
	FindAccount(ctx context.Context, accountID, ownerID string) (*AccountView, error)
}
