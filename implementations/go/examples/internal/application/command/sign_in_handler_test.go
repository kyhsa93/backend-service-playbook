package command_test

import (
	"context"
	"errors"
	"testing"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/domain/credential"
)

func TestSignInHandler_Handle_ValidCredentials_ReturnsAccessToken(t *testing.T) {
	stored := credential.New("owner-1", "hashed-password")
	repo := &stubCredentialRepository{
		findByUserIDFn: func(ctx context.Context, userID string) (*credential.Credential, error) { return stored, nil },
	}
	hasher := &stubPasswordHasher{
		verifyFn: func(plainPassword, passwordHash string) (bool, error) { return true, nil },
	}
	tokenIssuer := &stubTokenIssuer{}
	handler := command.NewSignInHandler(repo, hasher, tokenIssuer)

	accessToken, err := handler.Handle(context.Background(), command.SignInCommand{UserID: "owner-1", Password: "plain-password"})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if accessToken != "access-token-for-owner-1" {
		t.Fatalf("accessToken = %s, want access-token-for-owner-1", accessToken)
	}
}

func TestSignInHandler_Handle_NonExistentUserID_ReturnsErrInvalidCredentials(t *testing.T) {
	repo := &stubCredentialRepository{
		findByUserIDFn: func(ctx context.Context, userID string) (*credential.Credential, error) {
			return nil, credential.ErrNotFound
		},
	}
	handler := command.NewSignInHandler(repo, &stubPasswordHasher{}, &stubTokenIssuer{
		signFn: func(userID string) (string, error) {
			t.Fatal("want tokenIssuer.Sign not to be called for a non-existent userId")
			return "", nil
		},
	})

	_, err := handler.Handle(context.Background(), command.SignInCommand{UserID: "no-such-user", Password: "plain-password"})

	if !errors.Is(err, credential.ErrInvalidCredentials) {
		t.Fatalf("err = %v, want credential.ErrInvalidCredentials", err)
	}
}

func TestSignInHandler_Handle_WrongPassword_ReturnsErrInvalidCredentials(t *testing.T) {
	stored := credential.New("owner-1", "hashed-password")
	repo := &stubCredentialRepository{
		findByUserIDFn: func(ctx context.Context, userID string) (*credential.Credential, error) { return stored, nil },
	}
	hasher := &stubPasswordHasher{
		verifyFn: func(plainPassword, passwordHash string) (bool, error) { return false, nil },
	}
	handler := command.NewSignInHandler(repo, hasher, &stubTokenIssuer{
		signFn: func(userID string) (string, error) {
			t.Fatal("want tokenIssuer.Sign not to be called for a wrong password")
			return "", nil
		},
	})

	_, err := handler.Handle(context.Background(), command.SignInCommand{UserID: "owner-1", Password: "wrong-password"})

	if !errors.Is(err, credential.ErrInvalidCredentials) {
		t.Fatalf("err = %v, want credential.ErrInvalidCredentials", err)
	}
}
