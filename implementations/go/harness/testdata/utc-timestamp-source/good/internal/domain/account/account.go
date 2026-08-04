package account

import (
	"time"

	"github.com/example/account-service/internal/common"
)

type Account struct {
	AccountID string
	CreatedAt time.Time
}

// New stamps CreatedAt through the shared helper rather than time.Now(),
// which would carry the host's local location — this mention of the bare
// call sits in a comment and must not be counted as a violation.
func New(accountID string) *Account {
	return &Account{
		AccountID: accountID,
		CreatedAt: common.Now(),
	}
}
