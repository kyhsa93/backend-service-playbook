package command_test

import (
	"context"

	"github.com/example/account-service/internal/domain/credential"
)

// stubCredentialRepository is a minimal mock that injects only the behavior
// needed per test via function fields (same idiom as stubRepository in
// stub_test.go). It's enough for findByUserIDFn to mimic only the
// single-record lookup scenario wrapped by credential.FindOne, so the
// FindCredentials implementation wraps its result as a single-element slice
// ([]*credential.Credential of length 0 or 1).
type stubCredentialRepository struct {
	findByUserIDFn func(ctx context.Context, userID string) (*credential.Credential, error)
	saveFn         func(ctx context.Context, c *credential.Credential) error
}

func (s *stubCredentialRepository) FindCredentials(ctx context.Context, q credential.FindQuery) ([]*credential.Credential, error) {
	c, err := s.findByUserIDFn(ctx, q.UserID)
	if err != nil {
		return nil, err
	}
	if c == nil {
		return nil, nil
	}
	return []*credential.Credential{c}, nil
}

func (s *stubCredentialRepository) SaveCredential(ctx context.Context, c *credential.Credential) error {
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
