package config

import (
	"context"
	"fmt"
	"os"
)

const defaultRefundClassifierModel = "claude-opus-4-8"

// RefundClassifierModel returns the model id RefundReasonClassifierImpl uses, overridable via
// REFUND_CLASSIFIER_MODEL. All raw env var access for this feature is encapsulated here (never
// read directly inside domain/application/infrastructure code — config.md).
func RefundClassifierModel() string {
	if v := os.Getenv("REFUND_CLASSIFIER_MODEL"); v != "" {
		return v
	}
	return defaultRefundClassifierModel
}

// LoadAnthropicAPIKey looks up the Anthropic API key from Secrets Manager only when env is
// "production" — every other environment (development/test) uses the ANTHROPIC_API_KEY
// environment variable directly, with no network call. Same APP_ENV-gating convention as
// LoadJWTSecret (jwt.go); RefundReasonClassifierImpl (infrastructure/llm) never reads
// ANTHROPIC_API_KEY or APP_ENV itself — main.go resolves the key here once at startup and
// injects it.
func LoadAnthropicAPIKey(ctx context.Context, secretService SecretService, env string) (string, error) {
	if env != "production" {
		if v := os.Getenv("ANTHROPIC_API_KEY"); v != "" {
			return v, nil
		}
		return "dev-anthropic-key", nil
	}

	apiKey, err := secretService.GetSecret(ctx, "app/anthropic")
	if err != nil {
		return "", fmt.Errorf("load anthropic api key: %w", err)
	}
	return apiKey, nil
}
