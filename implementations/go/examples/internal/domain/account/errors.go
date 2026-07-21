package account

import "errors"

var (
	ErrNotFound                           = errors.New("account not found")
	ErrInvalidAmount                      = errors.New("amount must be greater than zero")
	ErrInvalidMoneyAmount                 = errors.New("money amount must be zero or greater")
	ErrCurrencyMismatch                   = errors.New("currency mismatch")
	ErrDepositRequiresActiveAccount       = errors.New("account must be active to deposit")
	ErrWithdrawRequiresActiveAccount      = errors.New("account must be active to withdraw")
	ErrInsufficientBalance                = errors.New("insufficient balance")
	ErrSuspendRequiresActiveAccount       = errors.New("account must be active to suspend")
	ErrReactivateRequiresSuspendedAccount = errors.New("account must be suspended to reactivate")
	ErrAlreadyClosed                      = errors.New("account already closed")
	ErrBalanceNotZero                     = errors.New("balance must be zero to close account")
	ErrInterestRequiresActiveAccount      = errors.New("account must be active to receive interest")
	ErrInvalidInterestDate                = errors.New("invalid interest posting date")
)
