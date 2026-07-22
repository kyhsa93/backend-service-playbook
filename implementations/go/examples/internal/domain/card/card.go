package card

import (
	"time"

	"github.com/example/account-service/internal/common"
)

// Card is the Aggregate Root representing an issued card. Card has no way
// to know whether the linked account (AccountID) is active — whether
// issuance is allowed (account status) is determined by the Application
// layer via a synchronous lookup through AccountAdapter (ACL) before it
// calls the IssueCard factory (see cross-domain.md).
type Card struct {
	CardID    string
	AccountID string
	OwnerID   string
	Brand     string
	Status    Status
	CreatedAt time.Time
	// LastStatementSentMonth is the period for which the monthly card usage
	// statement was last sent (in "2006-01" format, e.g. "2026-07"). An
	// empty string means one has never been sent. Same idiom as
	// Account.LastInterestPaidAt — this single field is enough to determine
	// "has this period already been sent" (Level 1 — inherent idempotency),
	// so if the same period's batch Task is re-run at-least-once, it becomes
	// a natural no-op.
	LastStatementSentMonth string
}

// IssueCard issues a new card in ACTIVE status (corresponds to nestjs's Card.issue()).
func IssueCard(accountID, ownerID, brand string) *Card {
	return &Card{
		CardID:    common.NewID(),
		AccountID: accountID,
		OwnerID:   ownerID,
		Brand:     brand,
		Status:    StatusActive,
		CreatedAt: time.Now(),
	}
}

// Reconstitute restores a row read from storage into a domain object (restored as-is, without invariant checks).
func Reconstitute(cardID, accountID, ownerID, brand string, status Status, createdAt time.Time, lastStatementSentMonth string) *Card {
	return &Card{
		CardID:                 cardID,
		AccountID:              accountID,
		OwnerID:                ownerID,
		Brand:                  brand,
		Status:                 status,
		CreatedAt:              createdAt,
		LastStatementSentMonth: lastStatementSentMonth,
	}
}

// Suspend suspends a card. An already-cancelled card cannot be suspended,
// and re-suspending an already-suspended card is also meaningless, so both
// are errors (same rule as nestjs's Card.suspend()).
func (c *Card) Suspend() error {
	if c.Status == StatusCancelled {
		return ErrCancelledCardCannotBeSuspended
	}
	if c.Status == StatusSuspended {
		return ErrAlreadySuspended
	}
	c.Status = StatusSuspended
	return nil
}

// Cancel cancels a card. Re-cancelling an already-cancelled card is an
// error. It can be cancelled from either ACTIVE or SUSPENDED status (same as
// nestjs's Card.cancel()).
func (c *Card) Cancel() error {
	if c.Status == StatusCancelled {
		return ErrAlreadyCancelled
	}
	c.Status = StatusCancelled
	return nil
}

// MarkStatementSent records that the monthly usage statement for period
// (in "2006-01" format) has been sent. If it's already recorded for the same
// period, nothing changes and it returns false (Level 1 — inherent
// idempotent no-op) — the caller (SendCardUsageStatementHandler) uses this
// signal to decide whether a Save is needed, based on whether this was
// actually a new send. Sending the notification itself (the SES call) is an
// external side effect Card knows nothing about, so the caller must call
// this method only after the send succeeds — that ordering ensures a retry
// (at-least-once) after a failed send actually attempts to send again.
func (c *Card) MarkStatementSent(period string) bool {
	if c.LastStatementSentMonth == period {
		return false
	}
	c.LastStatementSentMonth = period
	return true
}
