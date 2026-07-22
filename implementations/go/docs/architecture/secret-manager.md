# Secret Management (Go)

The principle follows the root [secret-manager.md](../../../../docs/architecture/secret-manager.md): sensitive values are never placed directly in environment variables — they're looked up at runtime from something like AWS Secrets Manager, and a TTL cache reduces repeated calls. The JWT secret is implemented with this pattern. The SES credentials are still read directly via `os.Getenv` (`ses_client.go`) — since these aren't sensitive values but the region/credentials themselves (which in production can be replaced by an IAM role), they're out of scope for this document.

---

## SecretService — a Technical Service interface

Applies the root's Technical Service pattern ([domain-service.md](../../../../docs/architecture/domain-service.md)) as-is: an interface in the Application layer, an implementation in the Infrastructure layer. This is the same structure as the pattern this repository already uses (`command.PasswordHasher` ← the bcrypt implementation).

```go
// internal/config/secret_service.go — actual code (the interface is defined in the consuming package)
package config

import "context"

// SecretService is an interface defined from the perspective of this package (config loading) —
// the implementation lives in internal/infrastructure/secret.
type SecretService interface {
	GetSecret(ctx context.Context, secretID string) (string, error)
}
```

---

## Infrastructure implementation — AWS Secrets Manager + TTL cache

```go
// internal/infrastructure/secret/service.go — actual code
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

// Service looks up Secrets Manager and caches the result in memory for the TTL.
// It's protected by sync.Mutex — multiple goroutines (request handlers) may
// look up the same key concurrently.
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

// NewSecretsManagerClient follows the same idiom as this repository's ses_client.go:
// explicit static credentials (to avoid IMDS latency) + branching to LocalStack via AWS_ENDPOINT_URL.
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

`sync.Mutex` + `map` is the simplest TTL cache implementation in Go — unlike Node's `Map`-based cache, Go requires a lock to handle concurrent access (multiple HTTP request goroutines looking up the same secret at once). If the cache grows or an expiry sweep becomes necessary, this can be swapped for a library like `github.com/patrickmn/go-cache`, but at this repository's scale — a small number of secrets — the implementation above is sufficient.

---

## Secrets in JSON form

As with the root document, values that are logically grouped (the full DB connection info) are stored as JSON in a single secret.

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

## Usage in the config factory — branching by environment

Looked up only once, at the point where the config struct discussed in [config.md](config.md) is created.

```go
// internal/config/database.go — target shape. Uses the same polarity (env != "production")
// as the actual LoadJWTSecret (config/jwt.go).
func LoadDatabaseConfig(ctx context.Context, secretService SecretService, env string) (DatabaseConfig, error) {
	if env != "production" {
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

**A cross-language difference — the gating variable name and polarity differ**: this repository (and the actual `LoadJWTSecret`) gates on `env != "production"` (the variable `APP_ENV`, "if not production, it's local"). nestjs uses a different variable name (`NODE_ENV`) but the same polarity. fastapi uses the same variable name as this repository, `APP_ENV`, but the **opposite polarity** (`== "production"`, "if production, use the cloud"). kotlin/java-springboot gate not on an environment variable but on a Spring **profile** (`Profiles.of("prod")`). When consulting another language's document, don't assume the name and polarity map over directly.

---

## Local development — LocalStack

Following the same approach as [local-dev.md](local-dev.md), add `secretsmanager` to `docker-compose.yml`'s `SERVICES` and add an initialization script:

```yaml
# docker-compose.yml — add secretsmanager to SERVICES (currently only ses is present)
localstack:
  image: localstack/localstack:3.0
  environment:
    SERVICES: ses,secretsmanager
```

```sh
#!/bin/sh
# localstack/init-secrets.sh — mounted into ready.d the same way as init-ses.sh
set -e
awslocal secretsmanager create-secret \
  --name app/database \
  --secret-string '{"host":"database","port":5432,"username":"dev","password":"dev"}'
```

---

### Related documents

- [config.md](config.md) — criteria for environment variables vs Secrets Manager, fail-fast validation
- [local-dev.md](local-dev.md) — the LocalStack setup pattern (already demonstrated with SES)
- [container.md](container.md) — the principle of never baking secrets into the image in production
