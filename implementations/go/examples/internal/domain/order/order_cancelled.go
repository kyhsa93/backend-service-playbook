package order

import "time"

type OrderCancelled struct {
	OrderID     string
	Reason      string
	CancelledAt time.Time
}
