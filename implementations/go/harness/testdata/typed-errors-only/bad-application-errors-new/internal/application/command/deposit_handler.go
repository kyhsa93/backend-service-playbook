package command

import "errors"

// application/ should reuse domain's sentinel instead of declaring a new one,
// but here it calls errors.New directly — a violation case.
func Validate(amount int64) error {
	if amount <= 0 {
		return errors.New("invalid amount")
	}
	return nil
}
