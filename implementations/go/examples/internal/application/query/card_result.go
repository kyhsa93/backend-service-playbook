package query

import "time"

type GetCardResult struct {
	CardID    string
	AccountID string
	OwnerID   string
	Brand     string
	Status    string
	CreatedAt time.Time
}
