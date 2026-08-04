package persistence

import "time"

// SaveAccount converts the reading inline instead of going through the
// shared helper — still a UTC value, so the rule accepts it (an external
// project need not have adopted a common clock package).
func SaveAccount(accountID string) (string, time.Time) {
	return accountID, time.Now().UTC()
}
