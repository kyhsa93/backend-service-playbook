package card_test

import (
	"errors"
	"regexp"
	"testing"

	"github.com/example/account-service/internal/domain/card"
)

var hex32 = regexp.MustCompile(`^[0-9a-f]{32}$`)

func TestIssueCard(t *testing.T) {
	c := card.IssueCard("acc-1", "owner-1", "VISA")

	if c.Status != card.StatusActive {
		t.Fatalf("Status = %v, want StatusActive", c.Status)
	}
	if c.AccountID != "acc-1" || c.OwnerID != "owner-1" || c.Brand != "VISA" {
		t.Fatalf("unexpected card fields: %+v", c)
	}
	if !hex32.MatchString(c.CardID) {
		t.Fatalf("CardID = %q, want 32-char hex string without hyphens", c.CardID)
	}
	if c.CreatedAt.IsZero() {
		t.Fatal("CreatedAt should be set")
	}
}

func TestCard_Suspend(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *card.Card
		wantErr error
		want    card.Status
	}{
		{
			name:    "suspending_an_active_card_becomes_SUSPENDED",
			setup:   func() *card.Card { return card.IssueCard("acc-1", "owner-1", "VISA") },
			wantErr: nil,
			want:    card.StatusSuspended,
		},
		{
			name: "suspending_an_already_suspended_card_errors",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Suspend()
				return c
			},
			wantErr: card.ErrAlreadySuspended,
			want:    card.StatusSuspended,
		},
		{
			name: "a_cancelled_card_cannot_be_suspended",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Cancel()
				return c
			},
			wantErr: card.ErrCancelledCardCannotBeSuspended,
			want:    card.StatusCancelled,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := tt.setup()
			err := c.Suspend()
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Suspend() error = %v, want %v", err, tt.wantErr)
			}
			if c.Status != tt.want {
				t.Fatalf("Status = %v, want %v", c.Status, tt.want)
			}
		})
	}
}

func TestCard_MarkStatementSent(t *testing.T) {
	t.Run("first_send_for_a_period_returns_true_and_records_it", func(t *testing.T) {
		c := card.IssueCard("acc-1", "owner-1", "VISA")

		if changed := c.MarkStatementSent("2026-07"); !changed {
			t.Fatal("MarkStatementSent() = false, want true for first send")
		}
		if c.LastStatementSentMonth != "2026-07" {
			t.Fatalf("LastStatementSentMonth = %q, want %q", c.LastStatementSentMonth, "2026-07")
		}
	})

	t.Run("resending_the_same_period_idempotently_returns_false", func(t *testing.T) {
		c := card.IssueCard("acc-1", "owner-1", "VISA")
		_ = c.MarkStatementSent("2026-07")

		if changed := c.MarkStatementSent("2026-07"); changed {
			t.Fatal("MarkStatementSent() = true on repeat period, want false (idempotent no-op)")
		}
		if c.LastStatementSentMonth != "2026-07" {
			t.Fatalf("LastStatementSentMonth = %q, want %q", c.LastStatementSentMonth, "2026-07")
		}
	})

	t.Run("the_next_period_returns_true_again", func(t *testing.T) {
		c := card.IssueCard("acc-1", "owner-1", "VISA")
		_ = c.MarkStatementSent("2026-07")

		if changed := c.MarkStatementSent("2026-08"); !changed {
			t.Fatal("MarkStatementSent() = false for a new period, want true")
		}
		if c.LastStatementSentMonth != "2026-08" {
			t.Fatalf("LastStatementSentMonth = %q, want %q", c.LastStatementSentMonth, "2026-08")
		}
	})
}

func TestCard_Cancel(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *card.Card
		wantErr error
	}{
		{
			name:    "cancelling_an_active_card_becomes_CANCELLED",
			setup:   func() *card.Card { return card.IssueCard("acc-1", "owner-1", "VISA") },
			wantErr: nil,
		},
		{
			name: "a_suspended_card_can_also_be_cancelled",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Suspend()
				return c
			},
			wantErr: nil,
		},
		{
			name: "cancelling_an_already_cancelled_card_errors",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Cancel()
				return c
			},
			wantErr: card.ErrAlreadyCancelled,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c := tt.setup()
			err := c.Cancel()
			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Cancel() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantErr == nil && c.Status != card.StatusCancelled {
				t.Fatalf("Status = %v, want StatusCancelled", c.Status)
			}
		})
	}
}
