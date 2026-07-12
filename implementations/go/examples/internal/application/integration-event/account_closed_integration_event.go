package integrationevent

import "time"

type AccountClosedV1 struct {
	AccountID string    `json:"accountId"`
	ClosedAt  time.Time `json:"closedAt"`
}

func (AccountClosedV1) EventName() string { return "account.closed.v1" }
