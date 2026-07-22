package persistence

import "context"

// AccountRepository is the implementation of domain.Repository. Private/internal
// helper method names are out of scope for this rule — Repository/Query
// interfaces live only in the domain/ layer, so implementations under
// infrastructure/ are excluded from the scan.
type AccountRepository struct{}

func (r *AccountRepository) FindByID(ctx context.Context, id string) (*Account, error) {
	return nil, nil
}

func (r *AccountRepository) Save(ctx context.Context, a *Account) error {
	return nil
}
