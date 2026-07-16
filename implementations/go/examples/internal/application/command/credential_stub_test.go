package command_test

import (
	"context"

	"github.com/example/account-service/internal/domain/credential"
)

// stubCredentialRepository는 테스트별로 필요한 동작만 함수 필드로 주입받는 최소 mock이다
// (stub_test.go의 stubRepository와 동일한 관용구).
type stubCredentialRepository struct {
	findByUserIDFn func(ctx context.Context, userID string) (*credential.Credential, error)
	saveFn         func(ctx context.Context, c *credential.Credential) error
}

func (s *stubCredentialRepository) FindByUserID(ctx context.Context, userID string) (*credential.Credential, error) {
	return s.findByUserIDFn(ctx, userID)
}

func (s *stubCredentialRepository) Save(ctx context.Context, c *credential.Credential) error {
	if s.saveFn == nil {
		return nil
	}
	return s.saveFn(ctx, c)
}

type stubPasswordHasher struct {
	hashFn   func(plainPassword string) (string, error)
	verifyFn func(plainPassword, passwordHash string) (bool, error)
}

func (s *stubPasswordHasher) Hash(plainPassword string) (string, error) {
	if s.hashFn != nil {
		return s.hashFn(plainPassword)
	}
	return "hashed-" + plainPassword, nil
}

func (s *stubPasswordHasher) Verify(plainPassword, passwordHash string) (bool, error) {
	return s.verifyFn(plainPassword, passwordHash)
}

type stubTokenIssuer struct {
	signFn func(userID string) (string, error)
}

func (s *stubTokenIssuer) Sign(userID string) (string, error) {
	if s.signFn != nil {
		return s.signFn(userID)
	}
	return "access-token-for-" + userID, nil
}
