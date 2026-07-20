package account

import "errors"

var (
	ErrNotFound      = errors.New("account not found")
	ErrInvalidAmount = errors.New("amount must be greater than zero")
)
