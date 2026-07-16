package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/credential"
)

func TestSignUpHandler_Handle_HashesAndSavesNewCredential(t *testing.T) {
	var saved *credential.Credential
	repo := &stubCredentialRepository{
		findByUserIDFn: func(ctx context.Context, userID string) (*credential.Credential, error) {
			return nil, credential.ErrNotFound
		},
		saveFn: func(ctx context.Context, c *credential.Credential) error { saved = c; return nil },
	}
	hasher := &stubPasswordHasher{}
	handler := command.NewSignUpHandler(repo, hasher)

	err := handler.Handle(context.Background(), command.SignUpCommand{UserID: "owner-1", Password: "plain-password"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if saved == nil {
		t.Fatal("want repo.Save to be called")
	}
	if saved.UserID != "owner-1" {
		t.Fatalf("UserID = %s, want owner-1", saved.UserID)
	}
	if saved.PasswordHash != "hashed-plain-password" {
		t.Fatalf("PasswordHash = %s, want hashed-plain-password", saved.PasswordHash)
	}
}

func TestSignUpHandler_Handle_DuplicateUserID_ReturnsErrUserIDAlreadyExists(t *testing.T) {
	existing := credential.New("owner-1", "existing-hash")
	repo := &stubCredentialRepository{
		findByUserIDFn: func(ctx context.Context, userID string) (*credential.Credential, error) { return existing, nil },
		saveFn: func(ctx context.Context, c *credential.Credential) error {
			t.Fatal("want repo.Save not to be called for a duplicate userId")
			return nil
		},
	}
	handler := command.NewSignUpHandler(repo, &stubPasswordHasher{})

	err := handler.Handle(context.Background(), command.SignUpCommand{UserID: "owner-1", Password: "plain-password"})

	if !errors.Is(err, credential.ErrUserIDAlreadyExists) {
		t.Fatalf("err = %v, want credential.ErrUserIDAlreadyExists", err)
	}
}
