package config

import "context"

// SecretService is an interface defined from the perspective of this
// package (config loading) as its consumer — the implementation lives in
// internal/infrastructure/secret.
type SecretService interface {
	GetSecret(ctx context.Context, secretID string) (string, error)
}
