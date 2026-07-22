package command

import (
	"context"
	"errors"

	"github.com/example/account-service/internal/domain/credential"
)

type SignUpCommand struct {
	UserID   string
	Password string
}

type SignUpHandler struct {
	repo           credential.Repository
	passwordHasher PasswordHasher
}

func NewSignUpHandler(repo credential.Repository, passwordHasher PasswordHasher) *SignUpHandler {
	return &SignUpHandler{repo: repo, passwordHasher: passwordHasher}
}

// Handle checks for user ID duplication, then hashes and stores the
// password. Unlike sign-in, a duplicate user ID is reported to the client
// as-is — at the sign-up step, the "this user ID is already taken" UX takes
// priority over preventing user enumeration (the same judgment as the
// nestjs implementation).
func (h *SignUpHandler) Handle(ctx context.Context, cmd SignUpCommand) error {
	_, err := credential.FindOne(ctx, h.repo, cmd.UserID)
	if err == nil {
		return credential.ErrUserIDAlreadyExists
	}
	if !errors.Is(err, credential.ErrNotFound) {
		return err
	}

	passwordHash, err := h.passwordHasher.Hash(cmd.Password)
	if err != nil {
		return err
	}
	return h.repo.SaveCredential(ctx, credential.New(cmd.UserID, passwordHash))
}
