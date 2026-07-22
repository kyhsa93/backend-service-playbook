# Environment Configuration Management (Go)

The principle follows the root [config.md](../../../../docs/architecture/config.md): validate required environment variables at startup and fail-fast (exit immediately) on failure. Configuration is split by concern, and sensitive values are managed via Secrets Manager (see [secret-manager.md](secret-manager.md)).

---

## `DATABASE_URL` fail-fast validation

Before `cmd/server/main.go` calls `sql.Open`, `config.LoadDatabaseConfig()` validates that `DATABASE_URL` exists. `sql.Open` itself doesn't error even with an empty DSN (because of `database/sql`'s characteristic of deferring evaluation until the driver actually attempts a connection) — so it must be validated separately at startup.

---

## Config structs — split by concern

Go has no framework like `@nestjs/config`'s `ConfigModule`. Instead, a **plain struct** is defined per concern, and each struct's constructor function (`Load...`) reads and validates the environment variables.

```go
// internal/config/database.go — actual code
package config

import (
	"fmt"
	"os"
)

type DatabaseConfig struct {
	URL string
}

func LoadDatabaseConfig() (DatabaseConfig, error) {
	url := os.Getenv("DATABASE_URL")
	if url == "" {
		return DatabaseConfig{}, fmt.Errorf("config: DATABASE_URL is required")
	}
	return DatabaseConfig{URL: url}, nil
}
```

The JWT secret isn't a separate `JWTConfig` struct — it's implemented as a function that also includes the Secrets Manager branch (see [secret-manager.md](secret-manager.md)):

```go
// internal/config/jwt.go — actual code
func LoadJWTSecret(ctx context.Context, secretService SecretService, env string) (string, error) {
	if env != "production" {
		if v := os.Getenv("JWT_SECRET"); v != "" {
			return v, nil
		}
		return "dev-secret", nil
	}
	// production: looked up from Secrets Manager (app/jwt)
	// ...
}
```

The AWS region/endpoint/credentials aren't extracted into a separate `AWSConfig` struct — `NewSESClient()` in `internal/infrastructure/notification/ses_client.go` and `NewSecretsManagerClient()` in `internal/infrastructure/secret/service.go` each inline-implement the same "env var + default value" pattern (`AWS_REGION` defaults to `us-east-1`, `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` default to `test`, `AWS_ENDPOINT_URL` has no default — if empty it means real AWS). Since the logic in both places is identical, extraction into a shared `AWSConfig` should be considered once a third consumer appears (YAGNI).

---

## Fail-fast — validate at startup, then exit immediately

If a `Load...` function returns an error, `main.go` logs it via structured logging (`slog.Error`) and exits immediately with `os.Exit(1)`.

```go
// cmd/server/main.go — actual code
func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

	dbConfig, err := config.LoadDatabaseConfig()
	if err != nil {
		slog.Error("config error", "error", err)
		os.Exit(1)
	}
	db, err := sql.Open("postgres", dbConfig.URL)
	// ...

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	// ...
}
```

`slog.Error` + `os.Exit(1)` is used instead of `log.Fatalf` to keep this aligned with the structured-logging principle in [observability.md](observability.md) — the exit behavior itself (`os.Exit(1)`) is the same either way.

---

## Default value principles

- Values that are safe to default for local development (`AWS_REGION=us-east-1`, `AWS_ACCESS_KEY_ID=test`) allow a fallback.
- Values that must never be empty in production (`DATABASE_URL`) have no default and return an error if the string is empty. `JWT_SECRET` is excluded from this principle since in production it's replaced by Secrets Manager rather than an environment variable (see [secret-manager.md](secret-manager.md)).

---

## Sensitive values — environment variables vs Secrets Manager

| Item | Recommended approach |
|------|----------|
| General config (hostname, port, region) | Environment variables |
| Sensitive values (DB password, JWT secret) | Secrets Manager |

Local development/testing uses environment variables or LocalStack; production uses AWS Secrets Manager. See [secret-manager.md](secret-manager.md) for the lookup/caching implementation in Go.

---

## Configuration access only in the Infrastructure layer

Domain/Application packages never call `os.Getenv` directly. There must be no `os` import anywhere in `internal/domain/account/`, `internal/application/command/`, or `internal/application/query/` — the current code actually upholds this principle. Config values are assembled in `main.go` and injected as constructor arguments.

```go
// correct — assembled in main.go, injected into the Infrastructure implementation
sesClient := notification.NewSESClient() // reads config internally (Infrastructure)
notifier := notification.NewService(sesClient, db)

// incorrect — accessing an environment variable directly from the Application layer
func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	region := os.Getenv("AWS_REGION") // forbidden — Application must not know about environment variables
	// ...
}
```

This prohibition is automatically checked by `implementations/go/harness/no_direct_env_access.go` (the `no-direct-env-access-outside-config` rule) — it flags FAIL if `internal/domain/` or `internal/application/` calls `os.Getenv`/`os.LookupEnv` directly (`internal/config/` and `internal/infrastructure/` are not subject to this check).

---

## `.env` files are local-only

`.env.development` is included in `.gitignore` and never committed. The Go standard library doesn't automatically read `.env` files, so for local development, load it into the shell with a tool like `direnv` or `godotenv`, or use `docker compose --env-file`. See [local-dev.md](local-dev.md) for detailed configuration.

---

### Related documents

- [container.md](container.md) — injecting environment variables at container runtime
- [graceful-shutdown.md](graceful-shutdown.md) — startup order (config validation → resource connection → server start)
- [secret-manager.md](secret-manager.md) — Secrets Manager lookup/caching details
- [local-dev.md](local-dev.md) — local development environment setup
