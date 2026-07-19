package command_test

import (
	"context"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/account"
)

func TestCreateAccountHandler_Handle_Saves(t *testing.T) {
	var saved *account.Account
	repo := &stubRepository{
		saveFn: func(ctx context.Context, a *account.Account) error { saved = a; return nil },
	}
	handler := command.NewCreateAccountHandler(repo)

	a, err := handler.Handle(context.Background(), command.CreateAccountCommand{
		RequesterID: "owner-1", Email: "owner1@example.com", Currency: "KRW",
	})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if a.OwnerID != "owner-1" {
		t.Fatalf("OwnerID = %s, want owner-1", a.OwnerID)
	}
	if saved != a {
		t.Fatal("want repo.Save to be called with the created account")
	}
}
