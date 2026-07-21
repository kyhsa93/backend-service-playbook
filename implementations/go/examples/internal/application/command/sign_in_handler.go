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

// Handle은 저장된 해시와 비교해 비밀번호를 검증한 뒤에만 토큰을 발급한다. 아이디가
// 존재하지 않는 경우와 비밀번호가 틀린 경우를 동일한 에러(credential.ErrInvalidCredentials)로
// 응답한다 — 두 경우를 구분하면 공격자가 존재하는 아이디를 추측할 수 있다(user
// enumeration).
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
