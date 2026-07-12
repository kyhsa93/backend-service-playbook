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
			name:    "활성_카드를_정지하면_SUSPENDED가_된다",
			setup:   func() *card.Card { return card.IssueCard("acc-1", "owner-1", "VISA") },
			wantErr: nil,
			want:    card.StatusSuspended,
		},
		{
			name: "이미_정지된_카드를_다시_정지하면_에러",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Suspend()
				return c
			},
			wantErr: card.ErrAlreadySuspended,
			want:    card.StatusSuspended,
		},
		{
			name: "해지된_카드는_정지할_수_없다",
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

func TestCard_Cancel(t *testing.T) {
	tests := []struct {
		name    string
		setup   func() *card.Card
		wantErr error
	}{
		{
			name:    "활성_카드를_해지하면_CANCELLED가_된다",
			setup:   func() *card.Card { return card.IssueCard("acc-1", "owner-1", "VISA") },
			wantErr: nil,
		},
		{
			name: "정지된_카드도_해지할_수_있다",
			setup: func() *card.Card {
				c := card.IssueCard("acc-1", "owner-1", "VISA")
				_ = c.Suspend()
				return c
			},
			wantErr: nil,
		},
		{
			name: "이미_해지된_카드를_다시_해지하면_에러",
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
