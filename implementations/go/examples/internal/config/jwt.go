package config

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
)

type jwtSecret struct {
	Secret string `json:"secret"`
}

// LoadJWTSecret queries Secrets Manager (app/jwt) only when APP_ENV is
// production. Otherwise (development/test/default), it uses only environment
// variables and makes no network call — no test run ever calls real AWS
// unless APP_ENV is set.
func LoadJWTSecret(ctx context.Context, secretService SecretService, env string) (string, error) {
	if env != "production" {
		if v := os.Getenv("JWT_SECRET"); v != "" {
			return v, nil
		}
		return "dev-secret", nil
	}

	raw, err := secretService.GetSecret(ctx, "app/jwt")
	if err != nil {
		return "", fmt.Errorf("load jwt secret: %w", err)
	}
	var secret jwtSecret
	if err := json.Unmarshal([]byte(raw), &secret); err != nil {
		return "", fmt.Errorf("parse jwt secret: %w", err)
	}
	return secret.Secret, nil
}
