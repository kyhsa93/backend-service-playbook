package credential

import "context"

// FindQuery has only a single UserID filter — since UserID must be unique
// (checked for duplicates at sign-up), there's no need for Page/Take like
// account.FindQuery has. There's no use case for looking up multiple
// combined records, so pagination itself is unnecessary.
type FindQuery struct {
	UserID string
}

// Query is a Query-only interface that exposes only read-only lookup
// methods (the same CQRS separation idiom as account.Query —
// cqrs-pattern.md).
//
// Lookups are unified into a single FindCredentials method, following the
// root's find<Noun>s convention — there is no dedicated single-record
// lookup method. Callers use FindOne (a helper provided by this package) to
// call FindCredentials and pull out the first result (the same idiom as
// account.FindOne).
type Query interface {
	FindCredentials(ctx context.Context, q FindQuery) ([]*Credential, error)
}

// Repository is a Command-only interface that adds a write method
// (SaveCredential) on top of Query's read methods.
type Repository interface {
	Query
	SaveCredential(ctx context.Context, credential *Credential) error
}

// FindOne is a helper that wraps the repeated single-record lookup pattern
// (call FindCredentials, then pull out the first result, or ErrNotFound if
// there is none) (the same idiom as account.FindOne).
func FindOne(ctx context.Context, q Query, userID string) (*Credential, error) {
	credentials, err := q.FindCredentials(ctx, FindQuery{UserID: userID})
	if err != nil {
		return nil, err
	}
	if len(credentials) == 0 {
		return nil, ErrNotFound
	}
	return credentials[0], nil
}
