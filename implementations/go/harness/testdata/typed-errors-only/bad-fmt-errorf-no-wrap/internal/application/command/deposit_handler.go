package command

import "fmt"

// A free-form fmt.Errorf that doesn't wrap an existing typed error with %w — a violation case.
func Validate(amount int64) error {
	if amount <= 0 {
		return fmt.Errorf("invalid amount: %d", amount)
	}
	return nil
}
