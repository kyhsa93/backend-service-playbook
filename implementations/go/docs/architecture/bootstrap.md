# 앱 부트스트랩 (Go)

Go 전용 문서 — root에는 대응 문서가 없다(NestJS의 `main.ts`/`NestFactory`에 해당하는 "프레임워크 부트스트랩" 개념 자체가 Go에는 없다). 이 문서는 이 저장소의 `cmd/server/main.go`가 실제로 어떻게 애플리케이션을 조립하는지 정리한다. 설정 검증([config.md](config.md))과 graceful shutdown([graceful-shutdown.md](graceful-shutdown.md))은 이미 아래 "현재 코드"에 반영되어 있고, 미들웨어 체인([cross-cutting-concerns.md](cross-cutting-concerns.md)가 제시하는 `func(http.Handler) http.Handler` 체인)만 아직 `router.go`에 부분적으로만 있다.

---

## 현재 코드 — `cmd/server/main.go`

```go
package main

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"

	"github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/config"
	"github.com/example/account-service/internal/infrastructure/auth"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	"github.com/example/account-service/internal/infrastructure/secret"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))

	dbConfig, err := config.LoadDatabaseConfig()
	if err != nil {
		slog.Error("config error", "error", err)
		os.Exit(1)
	}

	db, err := sql.Open("postgres", dbConfig.URL)
	if err != nil {
		slog.Error("failed to connect to db", "error", err)
		os.Exit(1)
	}
	defer db.Close()

	// 의존성 조립 — 프레임워크 없이 생성자 체이닝
	notifier := notification.NewService(notification.NewSESClient(), db)

	outboxWriter := outbox.NewWriter()
	outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier).Handle,
	})

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)

	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	mux := httphandler.NewRouter(accountRepo, outboxRelay, jwtService)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// SIGTERM/SIGINT를 받으면 ctx가 취소된다.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done() // SIGTERM/SIGINT 수신까지 블록
	slog.Info("shutdown signal received")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
	slog.Info("server stopped")
	// defer db.Close()가 이 시점 이후에 실행됨 — HTTP 서버가 완전히 닫힌 뒤 DB 연결 정리
}
```

`main()` 하나가 NestJS의 `bootstrap()` 함수 + `AppModule` 조합 + `NestFactory.create()`를 전부 대신한다. 프레임워크가 없으므로 "부트스트랩"은 특별한 개념이 아니라 **평범한 Go 함수 하나가 위에서 아래로 순서대로 실행되는 것**뿐이다.

---

## 단계별 분해

| 단계 | 코드 | 역할 |
|---|---|---|
| 0. 로깅/설정 준비 | `slog.SetDefault(...)`, `config.LoadDatabaseConfig()` | JSON 구조화 로거를 가장 먼저 설정하고, 필수 환경 변수를 검증해 실패 시 `os.Exit(1)`([config.md](config.md), [observability.md](observability.md) 참고) |
| 1. 인프라 연결 | `sql.Open("postgres", dbConfig.URL)` | DB 커넥션 풀 생성(실제 연결은 lazy). `defer db.Close()`로 `main()` 종료 시 정리 예약 |
| 2. Infrastructure 조립 | `notification.NewService(...)`, `outbox.NewWriter()`, `outbox.NewRelay(db, handlers)`, `secret.NewService(...)`, `auth.NewJWTService(...)`, `persistence.NewAccountRepository(db, outboxWriter)` | `db` 하나를 여러 Infrastructure 구현체에 주입 — NestJS의 `@Global` DatabaseModule과 달리 그냥 변수를 함수 인자로 넘기는 것뿐이다. JWT secret은 `config.LoadJWTSecret`이 환경(APP_ENV)에 따라 환경 변수 또는 Secrets Manager에서 가져온다([secret-manager.md](secret-manager.md) 참고) |
| 3. 라우터/Handler 조립 | `httphandler.NewRouter(accountRepo, outboxRelay, jwtService)` | Repository/OutboxRelay/JWTService를 받아 내부에서 Command/Query Handler, `AccountHandler`, 인증·Correlation ID 미들웨어를 조립([router.go](#핵심--routergo가-di-컨테이너-역할을-대신한다) 참고) |
| 4. 서버 시작 | `go func() { srv.ListenAndServe() }()` | 별도 goroutine에서 시작해 `main()`이 블록되지 않고 곧바로 시그널 대기(`<-ctx.Done()`)로 넘어간다 |
| 5. 종료 시그널 대기 | `signal.NotifyContext` + `<-ctx.Done()` | SIGTERM/SIGINT 수신까지 블록 |
| 6. Graceful Shutdown | `srv.Shutdown(shutdownCtx)` | 새 연결은 거부하고 진행 중인 요청은 완료를 기다린 뒤 종료 — 상세는 [graceful-shutdown.md](graceful-shutdown.md) 참고 |

의존성 조립 순서는 **의존 방향과 정확히 일치**한다 — `db`(가장 하위) → `notifier`/`outboxWriter`/`outboxRelay`/`secretService`/`jwtService`/Repository(Infrastructure) → Handler(Application, `router.go` 내부) → `mux`(Interface) → `http.Server`. 어느 한 단계라도 순서를 바꾸면 컴파일이 실패한다(아직 만들어지지 않은 변수를 참조하게 되므로) — Go 컴파일러가 조립 순서 자체를 강제하는 셈이다.

---

## 핵심 — `router.go`가 DI 컨테이너 역할을 대신한다

```go
// internal/interface/http/router.go — 실제 코드(요약)
func NewRouter(repo account.Repository, outboxRelay command.OutboxRelay, jwtService *auth.JWTService) http.Handler {
	createAccountHandler := command.NewCreateAccountHandler(repo, outboxRelay)
	depositHandler := command.NewDepositHandler(repo, outboxRelay)
	// ... 나머지 Command/Query Handler도 동일하게 생성자 호출

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler, /* ... */)
	authHTTP := NewAuthHandler(jwtService)

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ... 인증이 필요한 나머지 엔드포인트

	mux := http.NewServeMux()
	mux.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	mux.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	mux.HandleFunc("POST /auth/sign-in", authHTTP.SignIn) // 인증 불필요
	return middleware.CorrelationID(mux) // 가장 바깥에서 감싸 인증 실패 응답에도 포함
}
```

반환 타입이 `*http.ServeMux`가 아니라 `http.Handler`인 이유는 `middleware.CorrelationID`가 `http.Handler`를 반환하기 때문이다 — 호출부(`main.go`, e2e 테스트의 `httptest.NewServer`)는 인터페이스로만 이 값을 쓰므로 영향이 없다. 인증/Correlation ID 미들웨어 상세는 [authentication.md](authentication.md), [cross-cutting-concerns.md](cross-cutting-concerns.md) 참고.

NestJS라면 `@Module({ providers: [...] })`가 이 배선을 선언적으로 대신하고 Nest의 DI 컨테이너가 런타임에 그래프를 풀어낸다. Go는 그런 컨테이너가 없으므로 **`NewRouter`가 곧 배선 코드 그 자체**다 — "이 Handler는 이 Repository와 이 OutboxRelay로 만든다"를 코드로 직접 나열한다. 배선 로직이 커지면(도메인이 늘어나면) `main.go`에서 `NewRouter`로, 다시 `router.go` 내부의 개별 생성자 호출들로 책임이 계층적으로 위임되지만, 그 어디에도 컨테이너·리플렉션·데코레이터는 없다 — 전부 평범한 함수 호출이다. 상세는 [module-pattern.md](module-pattern.md) 참고.

---

## Graceful Shutdown

config(fail-fast 검증), auth(JWT), secret(Secrets Manager), observability(구조화 로깅), Correlation ID, graceful shutdown(`signal.NotifyContext` + `Shutdown(ctx)`)까지 위 "현재 코드"에 모두 반영되어 있다. 시그널 처리와 종료 순서의 상세(readiness와의 관계, `terminationGracePeriodSeconds` 등)는 [graceful-shutdown.md](graceful-shutdown.md)를 참고한다 — 다만 liveness/readiness 엔드포인트(`/health/live`, `/health/ready`)는 아직 `router.go`에 없다는 점은 그 문서에 남은 격차로 명시되어 있다.

---

## 원칙

- **`main()`은 배선만 한다** — 비즈니스 로직이나 조건 분기를 넣지 않는다. 생성자를 순서대로 호출하고 서버를 시작할 뿐이다.
- **의존성 조립 순서 = 의존 방향**: 하위 레이어(DB) → Infrastructure → Application → Interface → 서버 시작.
- **DI 컨테이너 대신 생성자 체이닝**: 새 의존성이 필요하면 새 생성자 인자를 추가하고 호출부를 고친다 — 리플렉션도 데코레이터도 없다.
- **환경 변수 읽기는 부트스트랩 시점에 몰아서 검증**한다([config.md](config.md)) — Handler나 도메인 코드 안에서 `os.Getenv`를 흩어놓지 않는다.

---

### 관련 문서

- [config.md](config.md) — 환경 변수 fail-fast 검증
- [module-pattern.md](module-pattern.md) — 생성자 체이닝이 NestJS 모듈/DI를 대신하는 메커니즘 상세
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — 미들웨어 체인 조립
- [graceful-shutdown.md](graceful-shutdown.md) — `signal.NotifyContext` + `Shutdown(ctx)` 상세
- [container.md](container.md) — 컴파일된 바이너리를 컨테이너에서 PID 1로 실행
