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

// LoadJWTSecret은 APP_ENV가 production일 때만 Secrets Manager(app/jwt)를 조회한다.
// 그 외(개발/테스트 등 기본값)는 환경 변수만 사용해 네트워크 호출 없이 동작한다 —
// APP_ENV를 세팅하지 않는 한 어떤 테스트 실행도 실제 AWS를 호출하지 않는다.
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
