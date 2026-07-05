package order

import (
	"fmt"
	"time"

	"github.com/google/uuid"
)

type Status string

const (
	StatusPending   Status = "pending"
	StatusPaid      Status = "paid"
	StatusCancelled Status = "cancelled"
)

type ItemInput struct {
	ItemID   int
	Name     string
	Price    int
	Quantity int
}

type Order struct {
	OrderID  string
	UserID   string
	Items    []Item
	Status   Status
	events   []OrderCancelled
}

func New(userID string, inputs []ItemInput) (*Order, error) {
	if len(inputs) == 0 {
		return nil, ErrEmptyItems
	}
	items := make([]Item, 0, len(inputs))
	for _, in := range inputs {
		item, err := newItem(in.ItemID, in.Name, in.Price, in.Quantity)
		if err != nil {
			return nil, fmt.Errorf("invalid item %d: %w", in.ItemID, err)
		}
		items = append(items, item)
	}
	return &Order{
		OrderID: uuid.NewString(),
		UserID:  userID,
		Items:   items,
		Status:  StatusPending,
	}, nil
}

func Reconstitute(orderID, userID string, items []Item, status Status) *Order {
	return &Order{OrderID: orderID, UserID: userID, Items: items, Status: status}
}

func (o *Order) Cancel(reason string) error {
	if o.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	if o.Status == StatusPaid {
		return ErrPaidNotCancellable
	}
	o.Status = StatusCancelled
	o.events = append(o.events, OrderCancelled{
		OrderID:     o.OrderID,
		Reason:      reason,
		CancelledAt: time.Now(),
	})
	return nil
}

func (o *Order) TotalAmount() int {
	total := 0
	for _, item := range o.Items {
		total += item.Price * item.Quantity
	}
	return total
}

func (o *Order) DomainEvents() []OrderCancelled { return o.events }
func (o *Order) ClearEvents()                   { o.events = nil }
