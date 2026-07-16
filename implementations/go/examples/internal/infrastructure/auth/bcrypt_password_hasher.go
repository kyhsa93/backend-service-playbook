package auth

import (
	"errors"

	"golang.org/x/crypto/bcrypt"
)

// bcryptCost는 nestjs 구현(bcryptjs, salt rounds 12)과 동일한 작업 비용으로 맞춘다.
const bcryptCost = 12

// BcryptPasswordHasher는 command.PasswordHasher를 구조적으로 만족하는 구현체다 —
// Go는 명시적 implements 선언이 없으므로 Hash/Verify 시그니처만 맞으면 충분하다
// (jwt_service.go의 JWTService가 command.TokenIssuer를 만족하는 것과 동일한 관용구).
type BcryptPasswordHasher struct{}

func NewBcryptPasswordHasher() *BcryptPasswordHasher {
	return &BcryptPasswordHasher{}
}

func (h *BcryptPasswordHasher) Hash(plainPassword string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(plainPassword), bcryptCost)
	if err != nil {
		return "", err
	}
	return string(hash), nil
}

// Verify는 해시 불일치(ErrMismatchedHashAndPassword)를 에러가 아니라 false로
// 번역한다 — 호출부(SignInHandler)가 "비밀번호가 틀림"과 "검증 중 시스템 오류"를
// 구분해서 처리할 수 있도록 하기 위함이다.
func (h *BcryptPasswordHasher) Verify(plainPassword, passwordHash string) (bool, error) {
	err := bcrypt.CompareHashAndPassword([]byte(passwordHash), []byte(plainPassword))
	if err == nil {
		return true, nil
	}
	if errors.Is(err, bcrypt.ErrMismatchedHashAndPassword) {
		return false, nil
	}
	return false, err
}
