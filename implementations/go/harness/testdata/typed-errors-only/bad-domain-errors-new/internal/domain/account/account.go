package account

import "errors"

type Account struct {
	Status string
}

// Declares an ad-hoc sentinel in a file other than errors.go — a violation case.
func (a *Account) Deposit(amount int64) error {
	if amount <= 0 {
		return errors.New("amount must be greater than zero")
	}
	return nil
}
