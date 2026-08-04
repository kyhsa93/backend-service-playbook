package account

import "time"

type Account struct {
	AccountID string
	CreatedAt time.Time
}

func New(accountID string) *Account {
	return &Account{
		AccountID: accountID,
		CreatedAt: time.Now(),
	}
}

func (a *Account) Close() time.Time {
	return time.Now()
}
