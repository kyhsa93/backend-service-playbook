// Package integrationevent holds the Integration Event contracts (versioned
// public contracts) the Account BC exposes to external BCs (Card, etc.).
// They are kept separate from internal Domain Events (account.AccountSuspended,
// etc.) to keep names/schemas stable and to state a version explicitly —
// EventName() is used as the Outbox row's event_type, and the receiving
// side (Card) looks up its handler by this string.
package integrationevent

import "time"

type AccountSuspendedV1 struct {
	AccountID   string    `json:"accountId"`
	SuspendedAt time.Time `json:"suspendedAt"`
}

func (AccountSuspendedV1) EventName() string { return "account.suspended.v1" }
