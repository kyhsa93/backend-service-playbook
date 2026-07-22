package command

import (
	"context"
	"errors"

	"github.com/example/account-service/internal/domain/credential"
)

type SignInCommand struct {
	UserID   string
	Password string
}

type SignInHandler struct {
	repo           credential.Repository
	passwordHasher PasswordHasher
	tokenIssuer    TokenIssuer
}

func NewSignInHandler(repo credential.Repository, passwordHasher PasswordHasher, tokenIssuer TokenIssuer) *SignInHandler {
	return &SignInHandler{repo: repo, passwordHasher: passwordHasher, tokenIssuer: tokenIssuer}
}

// Handle issues a token only after verifying the password against the
// stored hash. It responds with the same error
// (credential.ErrInvalidCredentials) whether the user ID doesn't exist or
// the password is wrong — distinguishing the two would let an attacker
// guess which user IDs exist (user enumeration).
func (h *SignInHandler) Handle(ctx context.Context, cmd SignInCommand) (string, error) {
	c, err := credential.FindOne(ctx, h.repo, cmd.UserID)
	if err != nil {
		if errors.Is(err, credential.ErrNotFound) {
			return "", credential.ErrInvalidCredentials
		}
		return "", err
	}

	valid, err := h.passwordHasher.Verify(cmd.Password, c.PasswordHash)
	if err != nil {
		return "", err
	}
	if !valid {
		return "", credential.ErrInvalidCredentials
	}

	return h.tokenIssuer.Sign(c.UserID)
}
