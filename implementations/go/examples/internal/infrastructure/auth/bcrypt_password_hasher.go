package auth

import (
	"errors"

	"golang.org/x/crypto/bcrypt"
)

// bcryptCost matches the same work factor as the nestjs implementation (bcryptjs, salt rounds 12).
const bcryptCost = 12

// BcryptPasswordHasher is an implementation that structurally satisfies
// command.PasswordHasher — Go has no explicit implements declaration, so
// matching the Hash/Verify signatures is enough (the same idiom as
// jwt_service.go's JWTService satisfying command.TokenIssuer).
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

// Verify translates a hash mismatch (ErrMismatchedHashAndPassword) into
// false rather than an error — this lets the caller (SignInHandler)
// distinguish between "wrong password" and "system error during
// verification."
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
