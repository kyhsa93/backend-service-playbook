package payment

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Payment is the Aggregate Root representing a payment. It only references
// which card/account is involved via CardID/AccountID (no FK crossing the
// BC boundary), and the actual judgment of whether the card is active or
// the account balance is sufficient is finished by the Application layer
// via synchronous lookups through CardAdapter/AccountAdapter (ACL) before
// this Aggregate is even created — Payment itself knows nothing about the
// basis for that judgment.
type Payment struct {
	PaymentID string
	CardID    string
	AccountID string
	OwnerID   string
	Amount    int64
	Status    Status
	CreatedAt time.Time
	events    []DomainEvent
}

// New is a pure creation factory that makes a payment in PENDING status. It
// is called only after the Application layer's synchronous Adapter calls
// have already finished judging whether the card is active and the account
// balance is sufficient — it does not raise an event (Complete() raises
// PaymentCompleted at the moment of completion).
func New(cardID, accountID, ownerID string, amount int64) *Payment {
	return &Payment{
		PaymentID: common.NewID(),
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Amount:    amount,
		Status:    StatusPending,
		CreatedAt: time.Now(),
	}
}

// Reconstitute restores a row read from storage into a domain object (restored as-is, without invariant checks).
func Reconstitute(paymentID, cardID, accountID, ownerID string, amount int64, status Status, createdAt time.Time) *Payment {
	return &Payment{
		PaymentID: paymentID,
		CardID:    cardID,
		AccountID: accountID,
		OwnerID:   ownerID,
		Amount:    amount,
		Status:    status,
		CreatedAt: createdAt,
	}
}

// Complete marks a payment as completed. Currently, CreatePaymentHandler
// judges pass/fail via a synchronous Adapter before creation, so this is
// called immediately after New(), and there is no path where a payment is
// created PENDING and then fails. Still, the Aggregate keeps the state
// transition itself (verified with Domain unit tests) in preparation for a
// future scenario where the result arrives asynchronously, such as a
// payment gateway callback.
func (p *Payment) Complete() error {
	if p.Status != StatusPending {
		return ErrCompleteRequiresPendingPayment
	}
	p.Status = StatusCompleted
	p.events = append(p.events, PaymentCompleted{
		PaymentID:   p.PaymentID,
		CardID:      p.CardID,
		AccountID:   p.AccountID,
		OwnerID:     p.OwnerID,
		Amount:      p.Amount,
		CompletedAt: time.Now(),
	})
	return nil
}

// Fail marks a payment as failed. No Command currently calls this method
// (there is no asynchronous payment gateway callback flow yet) — it's kept
// for the completeness of the state transitions and verified only with
// Domain unit tests (a guard symmetric to Complete()).
func (p *Payment) Fail(reason string) error {
	if p.Status != StatusPending {
		return ErrFailRequiresPendingPayment
	}
	p.Status = StatusFailed
	return nil
}

// Cancel cancels a payment. Since cancelling reverses an already-finalized
// (COMPLETED) payment, it's only possible from COMPLETED. The Account BC
// subscribes to PaymentCancelled and executes the compensating credit.
func (p *Payment) Cancel(reason string) error {
	if p.Status != StatusCompleted {
		return ErrCancelRequiresCompletedPayment
	}
	p.Status = StatusCancelled
	p.events = append(p.events, PaymentCancelled{
		PaymentID:   p.PaymentID,
		AccountID:   p.AccountID,
		OwnerID:     p.OwnerID,
		Amount:      p.Amount,
		Reason:      reason,
		CancelledAt: time.Now(),
	})
	return nil
}

func (p *Payment) DomainEvents() []DomainEvent { return p.events }
func (p *Payment) ClearEvents()                { p.events = nil }
