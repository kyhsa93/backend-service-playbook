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

// Handle은 아이디 중복을 확인한 뒤 비밀번호를 해싱해 저장한다. sign-in과 달리
// 아이디 중복은 그대로 클라이언트에 알려준다 — 가입 단계에서는 user enumeration
// 방지보다 "이미 쓰이는 아이디입니다" UX가 우선이다(nestjs 구현과 동일한 판단).
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
