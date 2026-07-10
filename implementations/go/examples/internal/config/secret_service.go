package config

import "context"

// SecretService는 이 패키지(설정 로딩)가 사용하는 관점에서 정의한 인터페이스다 —
// 구현체는 internal/infrastructure/secret에 있다.
type SecretService interface {
	GetSecret(ctx context.Context, secretID string) (string, error)
}
