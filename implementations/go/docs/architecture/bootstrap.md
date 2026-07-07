# 앱 부트스트랩 (Go)

Go 전용 문서 — root에는 대응 문서가 없다(NestJS의 `main.ts`/`NestFactory`에 해당하는 "프레임워크 부트스트랩" 개념 자체가 Go에는 없다). 이 문서는 이 저장소의 `cmd/server/main.go`가 실제로 어떻게 애플리케이션을 조립하는지, 그리고 다른 문서들이 각자 제시하는 개선(설정 검증, graceful shutdown, 미들웨어 체인)을 더하면 최종적으로 `main.go`가 어떤 모양이 되는지를 한 곳에 정리한다.

---

## 현재 코드 — `cmd/server/main.go`

```go
package main

import (
	"database/sql"
	"log"
	"net/http"
	"os"

	_ "github.com/lib/pq"

	"github.com/example/account-service/internal/application/event"
	"github.com/example/account-service/internal/infrastructure/notification"
	"github.com/example/account-service/internal/infrastructure/outbox"
	"github.com/example/account-service/internal/infrastructure/persistence"
	httphandler "github.com/example/account-service/internal/interface/http"
)

func main() {
	db, err := sql.Open("postgres", os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
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

	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	mux := httphandler.NewRouter(accountRepo, outboxRelay)

	addr := ":8080"
	log.Printf("listening on %s", addr)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatalf("server error: %v", err)
	}
}
```

`main()` 하나가 NestJS의 `bootstrap()` 함수 + `AppModule` 조합 + `NestFactory.create()`를 전부 대신한다. 프레임워크가 없으므로 "부트스트랩"은 특별한 개념이 아니라 **평범한 Go 함수 하나가 위에서 아래로 순서대로 실행되는 것**뿐이다.

---

## 단계별 분해

| 단계 | 코드 | 역할 |
|---|---|---|
| 1. 인프라 연결 | `sql.Open("postgres", os.Getenv("DATABASE_URL"))` | DB 커넥션 풀 생성(실제 연결은 lazy). `defer db.Close()`로 `main()` 종료 시 정리 예약 |
| 2. Infrastructure 조립 | `notification.NewService(...)`, `outbox.NewWriter()`, `outbox.NewRelay(db, handlers)`, `persistence.NewAccountRepository(db, outboxWriter)` | `db` 하나를 여러 Infrastructure 구현체에 주입 — NestJS의 `@Global` DatabaseModule과 달리 그냥 변수를 함수 인자로 넘기는 것뿐이다 |
| 3. 라우터/Handler 조립 | `httphandler.NewRouter(accountRepo, outboxRelay)` | Repository/OutboxRelay를 받아 내부에서 Command/Query Handler와 `AccountHandler`를 생성자 체이닝으로 조립([router.go](#핵심--routergo가-di-컨테이너-역할을-대신한다) 참고) |
| 4. 서버 시작 | `http.ListenAndServe(addr, mux)` | 블로킹 호출. 반환되면(에러 포함) 프로세스가 종료 단계로 넘어간다 |

의존성 조립 순서는 **의존 방향과 정확히 일치**한다 — `db`(가장 하위) → `notifier`/`outboxWriter`/`outboxRelay`/Repository(Infrastructure) → Handler(Application, `router.go` 내부) → `mux`(Interface) → `http.Server`. 어느 한 단계라도 순서를 바꾸면 컴파일이 실패한다(아직 만들어지지 않은 변수를 참조하게 되므로) — Go 컴파일러가 조립 순서 자체를 강제하는 셈이다.

---

## 핵심 — `router.go`가 DI 컨테이너 역할을 대신한다

```go
// internal/interface/http/router.go
func NewRouter(repo account.Repository, outboxRelay command.OutboxRelay) *http.ServeMux {
	createAccountHandler := command.NewCreateAccountHandler(repo, outboxRelay)
	depositHandler := command.NewDepositHandler(repo, outboxRelay)
	// ... 나머지 Command/Query Handler도 동일하게 생성자 호출

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler, /* ... */)

	mux := http.NewServeMux()
	mux.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ...
	return mux
}
```

NestJS라면 `@Module({ providers: [...] })`가 이 배선을 선언적으로 대신하고 Nest의 DI 컨테이너가 런타임에 그래프를 풀어낸다. Go는 그런 컨테이너가 없으므로 **`NewRouter`가 곧 배선 코드 그 자체**다 — "이 Handler는 이 Repository와 이 OutboxRelay로 만든다"를 코드로 직접 나열한다. 배선 로직이 커지면(도메인이 늘어나면) `main.go`에서 `NewRouter`로, 다시 `router.go` 내부의 개별 생성자 호출들로 책임이 계층적으로 위임되지만, 그 어디에도 컨테이너·리플렉션·데코레이터는 없다 — 전부 평범한 함수 호출이다. 상세는 [module-pattern.md](module-pattern.md) 참고.

---

## 목표 형태 — 다른 문서들의 개선을 통합한 `main()`

아래는 [config.md](config.md)(fail-fast 환경 변수 검증), [cross-cutting-concerns.md](cross-cutting-concerns.md)(미들웨어 체인), [graceful-shutdown.md](graceful-shutdown.md)(`signal.NotifyContext` + `Shutdown(ctx)`)가 각자 제시하는 목표 구현을 하나의 `main()`으로 합친 모습이다. 각 조각의 상세 근거와 코드는 해당 문서를 참고 — 이 문서는 "합쳤을 때 순서가 어떻게 되는가"에 집중한다.

```go
func main() {
	// 1. 설정 로드 + fail-fast 검증 (config.md)
	dbConfig, err := config.LoadDatabaseConfig()
	if err != nil {
		log.Fatalf("config: %v", err)
	}

	// 2. 인프라 연결
	db, err := sql.Open("postgres", dbConfig.URL)
	if err != nil {
		log.Fatalf("failed to connect to db: %v", err)
	}
	defer db.Close()

	// 3. Infrastructure 조립
	notifier := notification.NewService(notification.NewSESClient(), db)
	outboxWriter := outbox.NewWriter()
	outboxRelay := outbox.NewRelay(db, map[string]outbox.Handler{ /* ... 이벤트 타입별 핸들러 ... */ })
	accountRepo := persistence.NewAccountRepository(db, outboxWriter)

	// 4. Interface 조립 — 미들웨어 체인으로 감싼다 (cross-cutting-concerns.md)
	health := httphandler.NewHealthHandler()
	router := httphandler.NewRouter(accountRepo, outboxRelay, health)
	handler := middleware.Chain(router,
		middleware.CorrelationID,
		middleware.RequireAuth(jwtService),
		middleware.RequestLogging(logger),
	)

	srv := &http.Server{Addr: ":8080", Handler: handler}

	// 5. 시그널 기반 graceful shutdown (graceful-shutdown.md)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		log.Printf("listening on %s", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-ctx.Done()
	health.StartShutdown() // readiness를 503으로 먼저 전환
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("graceful shutdown failed: %v", err)
	}
}
```

**이 저장소의 현재 `examples/`는 위 1·4·5단계를 아직 구현하지 않았다** — 실제 코드는 문서 맨 위의 "현재 코드" 그대로다(`os.Getenv` 직접 사용, 미들웨어 체인 없음, `ListenAndServe` 블로킹 호출 그대로). 각 개선을 실제로 적용할 때는 해당 항목의 문서(config.md/cross-cutting-concerns.md/graceful-shutdown.md)를 먼저 읽는다 — 이 문서는 그 조각들이 최종적으로 어떤 순서로 `main()`에 모이는지 보여주는 통합 지도 역할만 한다.

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
