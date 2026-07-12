package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/card"
)

func TestIssueCardHandler_Handle(t *testing.T) {
	activeView := &command.AccountView{AccountID: "acc-1", Active: true}
	inactiveView := &command.AccountView{AccountID: "acc-1", Active: false}

	tests := []struct {
		name        string
		findAccount func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error)
		wantErr     error
		wantSaved   bool
	}{
		{
			name: "활성_계좌면_카드가_발급되고_저장된다",
			findAccount: func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
				return activeView, nil
			},
			wantErr:   nil,
			wantSaved: true,
		},
		{
			name: "계좌를_찾지_못하면_LinkedAccountNotFound_에러",
			findAccount: func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
				return nil, nil
			},
			wantErr:   card.ErrLinkedAccountNotFound,
			wantSaved: false,
		},
		{
			name: "비활성_계좌면_IssueRequiresActiveAccount_에러",
			findAccount: func(ctx context.Context, accountID, ownerID string) (*command.AccountView, error) {
				return inactiveView, nil
			},
			wantErr:   card.ErrIssueRequiresActiveAccount,
			wantSaved: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var saved *card.Card
			repo := &stubCardRepository{
				saveFn: func(ctx context.Context, c *card.Card) error { saved = c; return nil },
			}
			adapter := &stubAccountAdapter{findAccountFn: tt.findAccount}
			handler := command.NewIssueCardHandler(repo, adapter)

			c, err := handler.Handle(context.Background(), command.IssueCardCommand{
				AccountID: "acc-1", Brand: "VISA", RequesterID: "owner-1",
			})

			if !errors.Is(err, tt.wantErr) {
				t.Fatalf("Handle() error = %v, want %v", err, tt.wantErr)
			}
			if tt.wantSaved {
				if saved == nil {
					t.Fatal("want card to be saved")
				}
				if c == nil || c.Status != card.StatusActive {
					t.Fatalf("want ACTIVE card returned, got %+v", c)
				}
			} else if saved != nil {
				t.Fatal("want no card saved on failure")
			}
		})
	}
}

func TestSuspendCardsByAccountHandler_OnlyTouchesActiveCards(t *testing.T) {
	active := card.IssueCard("acc-1", "owner-1", "VISA")
	suspended := card.IssueCard("acc-1", "owner-1", "VISA")
	_ = suspended.Suspend()

	var savedIDs []string
	repo := &stubCardRepository{
		findAllFn: func(ctx context.Context, q card.FindQuery) ([]*card.Card, int, error) {
			// 핸들러가 ACTIVE 상태만 조회하는지 확인(멱등성의 근거).
			if len(q.Status) != 1 || q.Status[0] != card.StatusActive {
				t.Fatalf("want query for ACTIVE only, got %v", q.Status)
			}
			return []*card.Card{active}, 1, nil
		},
		saveFn: func(ctx context.Context, c *card.Card) error {
			savedIDs = append(savedIDs, c.CardID)
			return nil
		},
	}
	handler := command.NewSuspendCardsByAccountHandler(repo)

	if err := handler.Handle(context.Background(), command.SuspendCardsByAccountCommand{AccountID: "acc-1"}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if active.Status != card.StatusSuspended {
		t.Fatalf("active card Status = %v, want SUSPENDED", active.Status)
	}
	if len(savedIDs) != 1 || savedIDs[0] != active.CardID {
		t.Fatalf("want only the active card saved, got %v", savedIDs)
	}
	_ = suspended // 이미 정지된 카드는 조회 대상이 아니므로 건드리지 않는다.
}
