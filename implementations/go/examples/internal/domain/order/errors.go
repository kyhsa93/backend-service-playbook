package order

import "errors"

var (
	ErrNotFound           = errors.New("order not found")
	ErrAlreadyCancelled   = errors.New("order already cancelled")
	ErrPaidNotCancellable = errors.New("paid order cannot be cancelled")
	ErrEmptyItems         = errors.New("order must have at least one item")
	ErrInvalidPrice       = errors.New("item price must be greater than zero")
	ErrInvalidQuantity    = errors.New("item quantity must be greater than zero")
)
