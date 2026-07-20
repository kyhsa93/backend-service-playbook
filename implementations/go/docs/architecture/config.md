# 환경 설정 관리 (Go)

원칙은 루트 [config.md](../../../../docs/architecture/config.md)를 따른다: 기동 시 필수 환경 변수를 검증하고 실패하면 즉시 종료(fail-fast)한다. 관심사별로 설정을 분리하고, 민감값은 Secrets Manager로 관리한다([secret-manager.md](secret-manager.md) 참조).

---

## `DATABASE_URL` fail-fast 검증

`cmd/server/main.go`가 `sql.Open`을 호출하기 전에 `config.LoadDatabaseConfig()`로 `DATABASE_URL` 존재를 검증한다. `sql.Open` 자체는 DSN이 비어 있어도 에러를 내지 않는다(드라이버가 실제 연결을 시도할 때까지 지연 평가되는 `database/sql`의 특성 때문) — 그래서 기동 시점에 별도로 검증해야 한다.

---

## Config 구조체 — 관심사별 분리

Go에는 `@nestjs/config`의 `ConfigModule` 같은 프레임워크가 없다. 대신 관심사별 **plain struct**를 정의하고, 각 구조체의 생성자 함수(`Load...`)에서 환경 변수를 읽고 검증한다.

```go
// internal/config/database.go — 실제 코드
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

JWT secret은 별도의 `JWTConfig` 구조체가 아니라, Secrets Manager 분기까지 포함한 함수로 구현되어 있다([secret-manager.md](secret-manager.md) 참고):

```go
// internal/config/jwt.go — 실제 코드
func LoadJWTSecret(ctx context.Context, secretService SecretService, env string) (string, error) {
	if env != "production" {
		if v := os.Getenv("JWT_SECRET"); v != "" {
			return v, nil
		}
		return "dev-secret", nil
	}
	// production: Secrets Manager(app/jwt)에서 조회
	// ...
}
```

AWS 리전/엔드포인트/자격증명은 별도 `AWSConfig` 구조체로 추출하지 않았다 — `internal/infrastructure/notification/ses_client.go`의 `NewSESClient()`와 `internal/infrastructure/secret/service.go`의 `NewSecretsManagerClient()`가 각각 "환경 변수 + 기본값" 패턴(`AWS_REGION` 기본값 `us-east-1`, `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` 기본값 `test`, `AWS_ENDPOINT_URL`은 기본값 없이 비어 있으면 실제 AWS)을 인라인으로 반복 구현하고 있다. 두 곳의 로직이 완전히 동일하므로, 세 번째 소비자가 생기면 공용 `AWSConfig`로 추출을 검토한다(YAGNI).

---

## Fail-Fast — 기동 시 검증 후 즉시 종료

`Load...` 함수가 에러를 반환하면 `main.go`에서 구조화 로그(`slog.Error`)를 남기고 `os.Exit(1)`로 즉시 종료한다.

```go
// cmd/server/main.go — 실제 코드
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

`log.Fatalf` 대신 `slog.Error` + `os.Exit(1)`을 쓰는 이유는 [observability.md](observability.md)의 구조화 로깅 원칙과 일치시키기 위해서다 — 종료 동작 자체(`os.Exit(1)`)는 동일하다.

---

## 기본값 설정 원칙

- 로컬 개발에서 안전하게 쓸 수 있는 기본값(`AWS_REGION=us-east-1`, `AWS_ACCESS_KEY_ID=test`)은 fallback을 허용한다.
- 프로덕션에서 비어 있으면 안 되는 값(`DATABASE_URL`)은 기본값을 두지 않고 빈 문자열이면 에러를 반환한다. `JWT_SECRET`은 프로덕션에서는 환경 변수가 아니라 Secrets Manager로 대체되므로 이 원칙의 적용 대상에서 빠진다([secret-manager.md](secret-manager.md) 참고).

---

## 민감값 — 환경 변수 vs Secrets Manager

| 항목 | 권장 방식 |
|------|----------|
| 일반 설정 (호스트명, 포트, 리전) | 환경 변수 |
| 민감값 (DB 비밀번호, JWT secret) | Secrets Manager |

로컬 개발/테스트는 환경 변수 또는 LocalStack, 운영은 AWS Secrets Manager를 사용한다. Go에서의 조회/캐싱 구현은 [secret-manager.md](secret-manager.md) 참조.

---

## 설정 접근은 Infrastructure 레이어에서만

Domain/Application 패키지는 `os.Getenv`를 직접 호출하지 않는다. `internal/domain/account/`, `internal/application/command/`, `internal/application/query/` 어디에도 `os` import가 없어야 한다 — 실제로 현재 코드가 이 원칙을 지키고 있다. 설정값은 `main.go`에서 조립되어 생성자 인자로 주입된다.

```go
// 올바른 방식 — main.go에서 조립, Infrastructure 구현체에 주입
sesClient := notification.NewSESClient() // 내부적으로 config를 읽음 (Infrastructure)
notifier := notification.NewService(sesClient, db)

// 잘못된 방식 — Application 레이어에서 직접 환경 변수 접근
func (h *CreateAccountHandler) Handle(ctx context.Context, cmd CreateAccountCommand) (*account.Account, error) {
	region := os.Getenv("AWS_REGION") // 금지 — Application이 환경 변수를 알아서는 안 됨
	// ...
}
```

이 금지 규칙은 `implementations/go/harness/no_direct_env_access.go`(`no-direct-env-access-outside-config` 규칙)가 자동으로 검사한다 — `internal/domain/`, `internal/application/`이 `os.Getenv`/`os.LookupEnv`를 직접 호출하면 FAIL로 잡아낸다(`internal/config/`, `internal/infrastructure/`는 대상 아님).

---

## `.env` 파일은 로컬 전용

`.env.development`는 `.gitignore`에 포함하고 커밋하지 않는다. Go 표준 라이브러리는 `.env` 파일을 자동으로 읽지 않으므로, 로컬 개발 시 `direnv`, `godotenv` 같은 도구로 셸에 로드하거나 `docker compose --env-file`을 사용한다. 상세 구성은 [local-dev.md](local-dev.md) 참조.

---

### 관련 문서

- [container.md](container.md) — 컨테이너 실행 시 환경 변수 주입
- [graceful-shutdown.md](graceful-shutdown.md) — 기동 순서(설정 검증 → 리소스 연결 → 서버 시작)
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
