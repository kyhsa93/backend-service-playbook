package persistence

import "time"

func SaveAccount(accountID string) (string, time.Time) {
	return accountID, time.Now()
}
