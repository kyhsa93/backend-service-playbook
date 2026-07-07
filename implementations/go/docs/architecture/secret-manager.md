# Secret 관리 (Go)

원칙은 루트 [secret-manager.md](../../../../docs/architecture/secret-manager.md)를 따른다: 민감값은 환경 변수에 직접 두지 않고 AWS Secrets Manager 등에서 런타임에 조회하며, TTL 캐시로 반복 호출을 줄인다. **현재 `examples/`는 이 패턴을 전혀 쓰지 않는다** — SES 자격증명도 `os.Getenv`로 직접 읽는다(`ses_client.go`). 이 문서는 Go의 AWS SDK v2로 Secrets Manager + TTL 캐시를 어떻게 구현하는지 목표 형태로 제시한다.

---

## SecretService — Technical Service 인터페이스

root의 Technical Service 패턴([domain-service.md](../../../../docs/architecture/domain-service.md))을 그대로 적용한다: Application 레이어에 인터페이스, Infrastructure 레이어에 구현체. 이 저장소가 이미 쓰고 있는 패턴(`command.OutboxRelay` ← `outbox.Relay`)과 동일한 구조다.

```go
// internal/application/command/secret_service.go — 목표 형태 (인터페이스는 사용하는 쪽 패키지에 정의)
package command

import "context"

type SecretService interface {
	GetSecret(ctx context.Context, secretID string) (string, error)
}
```

---

## Infrastructure 구현체 — AWS Secrets Manager + TTL 캐시

```go
// internal/infrastructure/secret/service.go — 목표 형태
package secret

import (
	"context"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/secretsmanager"
)

type cacheEntry struct {
	value     string
	expiresAt time.Time
}

// Service는 Secrets Manager를 조회하고 결과를 TTL 동안 메모리에 캐시한다.
// sync.Mutex로 보호한다 — 여러 고루틴(요청 핸들러)이 동시에 같은 키를 조회할 수 있다.
type Service struct {
	client *secretsmanager.Client
	ttl    time.Duration

	mu    sync.Mutex
	cache map[string]cacheEntry
}

func NewService(client *secretsmanager.Client, ttl time.Duration) *Service {
	return &Service{client: client, ttl: ttl, cache: make(map[string]cacheEntry)}
}

func (s *Service) GetSecret(ctx context.Context, secretID string) (string, error) {
	s.mu.Lock()
	if entry, ok := s.cache[secretID]; ok && time.Now().Before(entry.expiresAt) {
		s.mu.Unlock()
		return entry.value, nil
	}
	s.mu.Unlock()

	out, err := s.client.GetSecretValue(ctx, &secretsmanager.GetSecretValueInput{SecretId: aws.String(secretID)})
	if err != nil {
		return "", fmt.Errorf("get secret %s: %w", secretID, err)
	}
	value := aws.ToString(out.SecretString)

	s.mu.Lock()
	s.cache[secretID] = cacheEntry{value: value, expiresAt: time.Now().Add(s.ttl)}
	s.mu.Unlock()

	return value, nil
}

// NewSecretsManagerClient는 이 저장소의 ses_client.go와 동일한 관용구를 따른다:
// 명시적 정적 자격증명(IMDS 지연 회피) + AWS_ENDPOINT_URL로 LocalStack 분기.
func NewSecretsManagerClient() *secretsmanager.Client {
	region := os.Getenv("AWS_REGION")
	if region == "" {
		region = "us-east-1"
	}
	accessKeyID := os.Getenv("AWS_ACCESS_KEY_ID")
	if accessKeyID == "" {
		accessKeyID = "test"
	}
	secretAccessKey := os.Getenv("AWS_SECRET_ACCESS_KEY")
	if secretAccessKey == "" {
		secretAccessKey = "test"
	}

	options := secretsmanager.Options{
		Region:      region,
		Credentials: credentials.NewStaticCredentialsProvider(accessKeyID, secretAccessKey, ""),
	}
	if endpoint := os.Getenv("AWS_ENDPOINT_URL"); endpoint != "" {
		options.BaseEndpoint = &endpoint
	}
	return secretsmanager.New(options)
}
```

`sync.Mutex` + `map`이 Go에서 가장 단순한 TTL 캐시 구현이다 — Node의 `Map` 기반 캐시와 달리 Go는 동시 접근(여러 HTTP 요청 고루틴이 같은 시크릿을 동시에 조회)에 대비해 락이 필수다. 캐시가 커지거나 만료 스윕이 필요해지면 `github.com/patrickmn/go-cache` 같은 라이브러리로 교체할 수 있지만, 시크릿 개수가 적은 이 저장소 규모에서는 위 구현으로 충분하다.

---

## JSON 형태의 시크릿

root와 동일하게 논리적으로 묶이는 값(DB 접속 정보 전체)은 하나의 시크릿에 JSON으로 저장한다.

```go
type databaseSecret struct {
	Host     string `json:"host"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
}

raw, err := secretService.GetSecret(ctx, "app/database")
if err != nil {
	return nil, fmt.Errorf("load database secret: %w", err)
}
var dbSecret databaseSecret
if err := json.Unmarshal([]byte(raw), &dbSecret); err != nil {
	return nil, fmt.Errorf("parse database secret: %w", err)
}
```

---

## Config 팩토리에서 사용 — 환경별 분기

[config.md](config.md)에서 다루는 설정 구조체 생성 시점에 한 번만 조회한다.

```go
// internal/config/database.go — 목표 형태
func LoadDatabaseConfig(ctx context.Context, secretService SecretService, env string) (DatabaseConfig, error) {
	if env == "development" {
		return DatabaseConfig{
			Host: mustEnv("DATABASE_HOST"), Port: 5432,
			Username: mustEnv("DATABASE_USER"), Password: mustEnv("DATABASE_PASSWORD"),
		}, nil
	}

	raw, err := secretService.GetSecret(ctx, "app/database")
	if err != nil {
		return DatabaseConfig{}, err
	}
	var secret databaseSecret
	if err := json.Unmarshal([]byte(raw), &secret); err != nil {
		return DatabaseConfig{}, fmt.Errorf("parse database secret: %w", err)
	}
	return DatabaseConfig{Host: secret.Host, Port: secret.Port, Username: secret.Username, Password: secret.Password}, nil
}
```

---

## 로컬 개발 — LocalStack

[local-dev.md](local-dev.md)와 동일한 방식으로 `docker-compose.yml`의 `SERVICES`에 `secretsmanager`를 추가하고 초기화 스크립트를 둔다:

```yaml
# docker-compose.yml — SERVICES에 secretsmanager 추가 (현재는 ses만 있음)
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager
```

```sh
#!/bin/sh
# localstack/init-secrets.sh — init-ses.sh와 같은 방식으로 ready.d에 마운트
set -e
awslocal secretsmanager create-secret \
  --name app/database \
  --secret-string '{"host":"database","port":5432,"username":"dev","password":"dev"}'
```

---

### 관련 문서

- [config.md](config.md) — 환경 변수 vs Secrets Manager 사용 기준, fail-fast 검증
- [local-dev.md](local-dev.md) — LocalStack 구성 패턴(SES에서 이미 실증됨)
- [container.md](container.md) — 운영 환경에서 시크릿을 이미지에 굽지 않는 원칙
