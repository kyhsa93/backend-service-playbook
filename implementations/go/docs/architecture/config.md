# 환경 설정 관리 (Go)

원칙은 루트 [config.md](../../../../docs/architecture/config.md)를 따른다: 기동 시 필수 환경 변수를 검증하고 실패하면 즉시 종료(fail-fast)한다. 관심사별로 설정을 분리하고, 민감값은 Secrets Manager로 관리한다([secret-manager.md](secret-manager.md) 참조).

---

## 알려진 편차 — 현재 예제는 검증 없이 `os.Getenv`를 직접 사용

`cmd/server/main.go`는 현재 다음과 같이 환경 변수를 검증 없이 바로 사용한다.

```go
// 현재 코드 — fail-fast 검증 없음
db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
```

`DATABASE_URL`이 비어 있어도 `sql.Open` 자체는 에러를 내지 않는다(드라이버가 실제 연결을 시도할 때까지 지연 평가되는 `database/sql`의 특성 때문). 즉 잘못된 설정이 기동 시점이 아니라 첫 쿼리 시점에야 발견된다 — root가 경계하는 "런타임에 예상치 못한 방식으로 실패"하는 사례 그 자체다. 아래는 이 문제를 해결하는 목표 패턴이다.

---

## Config 구조체 — 관심사별 분리

Go에는 `@nestjs/config`의 `ConfigModule` 같은 프레임워크가 없다. 대신 관심사별 **plain struct**를 정의하고, 각 구조체의 생성자 함수(`Load...Config`)에서 환경 변수를 읽고 검증한다.

```go
// internal/infrastructure/config/database_config.go
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

```go
// internal/infrastructure/config/jwt_config.go
package config

import (
	"fmt"
	"os"
	"time"
)

type JWTConfig struct {
	Secret string
	TTL    time.Duration
}

func LoadJWTConfig() (JWTConfig, error) {
	secret := os.Getenv("JWT_SECRET")
	if secret == "" {
		return JWTConfig{}, fmt.Errorf("config: JWT_SECRET is required")
	}
	return JWTConfig{Secret: secret, TTL: time.Hour}, nil
}
```

```go
// internal/infrastructure/config/aws_config.go — 기본값이 허용되는 로컬 개발용 값
package config

import "os"

type AWSConfig struct {
	Region          string
	EndpointURL     string // 비어 있으면 실제 AWS, 있으면 LocalStack
	AccessKeyID     string
	SecretAccessKey string
}

func LoadAWSConfig() AWSConfig {
	return AWSConfig{
		Region:          getEnvOr("AWS_REGION", "us-east-1"),
		EndpointURL:     os.Getenv("AWS_ENDPOINT_URL"),
		AccessKeyID:     getEnvOr("AWS_ACCESS_KEY_ID", "test"),
		SecretAccessKey: getEnvOr("AWS_SECRET_ACCESS_KEY", "test"),
	}
}

func getEnvOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
```

`internal/infrastructure/notification/ses_client.go`의 `NewSESClient()`가 이미 이 "환경 변수 + 기본값" 패턴을 인라인으로 구현하고 있다 — 위 `AWSConfig`는 이 로직을 재사용 가능한 구조체로 추출한 것이다.

---

## Fail-Fast — 기동 시 전체 검증 후 즉시 종료

각 `Load...Config` 함수가 개별 에러를 반환하면, `main.go`에서 이를 모아 하나라도 실패하면 `log.Fatal`로 즉시 종료한다. `log.Fatal`은 내부적으로 `os.Exit(1)`을 호출하므로 root의 "검증 실패 시 즉시 종료" 요구사항과 정확히 일치한다.

```go
// cmd/server/main.go — 목표 상태
package main

import (
	"log"

	"github.com/example/account-service/internal/infrastructure/config"
)

func main() {
	dbConfig, err := config.LoadDatabaseConfig()
	if err != nil {
		log.Fatalf("config error: %v", err) // 즉시 종료 — os.Exit(1)
	}
	jwtConfig, err := config.LoadJWTConfig()
	if err != nil {
		log.Fatalf("config error: %v", err)
	}
	awsConfig := config.LoadAWSConfig() // 기본값이 있으므로 에러 반환 없음

	// ... 검증을 모두 통과한 뒤에만 DB 연결, 서버 시작 등을 진행
}
```

**여러 설정 에러를 한 번에 보여주고 싶다면** `errors.Join`으로 모아서 한 번에 출력할 수 있다:

```go
func loadAllConfig() (dbCfg config.DatabaseConfig, jwtCfg config.JWTConfig, err error) {
	var errs []error
	dbCfg, dbErr := config.LoadDatabaseConfig()
	if dbErr != nil {
		errs = append(errs, dbErr)
	}
	jwtCfg, jwtErr := config.LoadJWTConfig()
	if jwtErr != nil {
		errs = append(errs, jwtErr)
	}
	return dbCfg, jwtCfg, errors.Join(errs...) // nil이면 join 결과도 nil
}

func main() {
	dbCfg, jwtCfg, err := loadAllConfig()
	if err != nil {
		log.Fatalf("config validation failed:\n%v", err) // 누락된 변수를 모두 한 번에 출력
	}
	// ...
}
```

---

## 기본값 설정 원칙

- 로컬 개발에서 안전하게 쓸 수 있는 기본값(`AWS_REGION=us-east-1`, `AWS_ACCESS_KEY_ID=test`)은 `getEnvOr`처럼 fallback을 허용한다.
- 프로덕션에서 비어 있으면 안 되는 값(`DATABASE_URL`, `JWT_SECRET`)은 기본값을 두지 않고 빈 문자열이면 에러를 반환한다.

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

---

## `.env` 파일은 로컬 전용

`.env.development`는 `.gitignore`에 포함하고 커밋하지 않는다. Go 표준 라이브러리는 `.env` 파일을 자동으로 읽지 않으므로, 로컬 개발 시 `direnv`, `godotenv` 같은 도구로 셸에 로드하거나 `docker compose --env-file`을 사용한다. 상세 구성은 [local-dev.md](local-dev.md) 참조.

---

### 관련 문서

- [container.md](container.md) — 컨테이너 실행 시 환경 변수 주입
- [graceful-shutdown.md](graceful-shutdown.md) — 기동 순서(설정 검증 → 리소스 연결 → 서버 시작)
- [secret-manager.md](secret-manager.md) — Secrets Manager 조회/캐싱 상세
- [local-dev.md](local-dev.md) — 로컬 개발 환경 구성
