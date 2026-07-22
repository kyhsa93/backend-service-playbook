# App Bootstrap (Go)

Go-specific document — there's no corresponding document at the root (Go has no concept of a "framework bootstrap" corresponding to NestJS's `main.ts`/`NestFactory`). This document lays out how this repository's `cmd/server/main.go` actually assembles the application. Config validation ([config.md](config.md)) and graceful shutdown ([graceful-shutdown.md](graceful-shutdown.md)) are already reflected in the "Current code" below, while the middleware chain (the `func(http.Handler) http.Handler` chain presented in [cross-cutting-concerns.md](cross-cutting-concerns.md)) still lives only partially in `router.go`.

---

## Current code — `cmd/server/main.go`

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

	// Dependency assembly — constructor chaining, no framework
	notifier := notification.NewService(notification.NewSESClient(), db)

	outboxWriter := outbox.NewWriter()
	sqsClient := outbox.NewSQSClient()
	sqsConfig, err := config.LoadSQSConfig()
	if err != nil {
		slog.Error("config error", "error", err)
		os.Exit(1)
	}

	// This handlers map is used by outbox.Consumer to look up the handler by the
	// eventType of a message received from SQS — the Command Handler never
	// references this map at all (synchronous draining is prohibited, see domain-events.md).
	outboxHandlers := map[string]outbox.Handler{
		"AccountCreated":     event.NewAccountCreatedEventHandler(notifier).Handle,
		"MoneyDeposited":     event.NewMoneyDepositedEventHandler(notifier).Handle,
		"MoneyWithdrawn":     event.NewMoneyWithdrawnEventHandler(notifier).Handle,
		"AccountSuspended":   event.NewAccountSuspendedEventHandler(notifier).Handle,
		"AccountReactivated": event.NewAccountReactivatedEventHandler(notifier).Handle,
		"AccountClosed":      event.NewAccountClosedEventHandler(notifier).Handle,
	}
	outboxPoller := outbox.NewPoller(db, sqsClient, sqsConfig.QueueURL)
	outboxConsumer := outbox.NewConsumer(sqsClient, sqsConfig.QueueURL, outboxHandlers)

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)

	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	mux := httphandler.NewRouter(accountRepo, jwtService)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// ctx is canceled on receiving SIGTERM/SIGINT — not just the HTTP server, but
	// the Poller/Consumer background loops below are also stopped by this same ctx.
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	// Outbox → SQS publish (Poller) and SQS → EventHandler receive (Consumer) run
	// independently on their own tick, unrelated to HTTP requests — the Command
	// Handler never references either (synchronous draining is prohibited, see
	// domain-events.md). Both stop themselves once ctx is canceled.
	go outboxPoller.Run(ctx)
	go outboxConsumer.Run(ctx)

	<-ctx.Done() // blocks until SIGTERM/SIGINT is received
	slog.Info("shutdown signal received")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}
	slog.Info("server stopped")
	// defer db.Close() runs after this point — DB connections are cleaned up only after the HTTP server has fully closed
}
```

One `main()` entirely replaces NestJS's `bootstrap()` function + `AppModule` composition + `NestFactory.create()`. With no framework, "bootstrap" isn't a special concept — it's just **one ordinary Go function running top to bottom in order**.

---

## Step-by-step breakdown

| Step | Code | Role |
|---|---|---|
| 0. Prepare logging/config | `slog.SetDefault(...)`, `config.LoadDatabaseConfig()` | Sets up the JSON structured logger first, then validates required environment variables and calls `os.Exit(1)` on failure (see [config.md](config.md), [observability.md](observability.md)) |
| 1. Connect infrastructure | `sql.Open("postgres", dbConfig.URL)` | Creates the DB connection pool (the actual connection is lazy). `defer db.Close()` schedules cleanup for when `main()` exits |
| 2. Assemble Infrastructure | `notification.NewService(...)`, `outbox.NewWriter()`, `outbox.NewSQSClient()`, `outbox.NewPoller(db, sqsClient, queueURL)`, `outbox.NewConsumer(sqsClient, queueURL, handlers)`, `secret.NewService(...)`, `auth.NewJWTService(...)`, `persistence.NewAccountRepository(db, outboxWriter)` | Injects the single `db`/`sqsClient` into multiple Infrastructure implementations — unlike NestJS's `@Global` DatabaseModule, it's just passing a variable as a function argument. The JWT secret is fetched by `config.LoadJWTSecret` from either an environment variable or Secrets Manager depending on the environment (APP_ENV) (see [secret-manager.md](secret-manager.md)) |
| 3. Assemble router/Handlers | `httphandler.NewRouter(accountRepo, jwtService)` | Takes the Repository/JWTService and internally assembles the Command/Query Handlers, `AccountHandler`, and the authentication/correlation-ID middleware (see the "Key point" section below). Outbox publish/consume are unrelated to the Command Handler, so they're never passed to `NewRouter` |
| 4. Start the server | `go func() { srv.ListenAndServe() }()`, `go outboxPoller.Run(ctx)`, `go outboxConsumer.Run(ctx)` | Each starts on its own independent goroutine, so `main()` isn't blocked and moves straight on to waiting for the signal (`<-ctx.Done()`). The HTTP server and the Poller/Consumer know nothing of each other — all three only share the same `ctx` for their stopping point |
| 5. Wait for the shutdown signal | `signal.NotifyContext` + `<-ctx.Done()` | Blocks until SIGTERM/SIGINT is received |
| 6. Graceful shutdown | `srv.Shutdown(shutdownCtx)` | Rejects new connections, waits for in-flight requests to finish, then shuts down — see [graceful-shutdown.md](graceful-shutdown.md) for details. The Poller/Consumer stop their own loops the moment `ctx` is canceled (no separate Shutdown call needed) |

The dependency assembly order **exactly matches the dependency direction** — `db` (the lowest level) → `notifier`/`outboxWriter`/`sqsClient`/`outboxPoller`/`outboxConsumer`/`secretService`/`jwtService`/Repository (Infrastructure) → Handler (Application, inside `router.go`) → `mux` (Interface) → `http.Server`. Reordering any step breaks compilation (since it would reference a variable that doesn't exist yet) — the Go compiler itself enforces the assembly order.

---

## Key point — `router.go` stands in for the DI container's role

```go
// internal/interface/http/router.go — actual code (summarized)
func NewRouter(repo account.Repository, jwtService *auth.JWTService) http.Handler {
	createAccountHandler := command.NewCreateAccountHandler(repo)
	depositHandler := command.NewDepositHandler(repo)
	// ... every remaining Command/Query Handler is created the same way via constructor calls —
	// Outbox publish/consume are unrelated to the Command Handler, so outboxPoller/outboxConsumer
	// aren't passed in here either

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler, /* ... */)
	authHTTP := NewAuthHandler(jwtService)

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ... the remaining endpoints that require authentication

	mux := http.NewServeMux()
	mux.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	mux.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
	mux.HandleFunc("POST /auth/sign-in", authHTTP.SignIn) // no authentication needed
	return middleware.CorrelationID(mux) // wraps at the outermost level so even auth-failure responses are covered
}
```

The reason the return type is `http.Handler` rather than `*http.ServeMux` is that `middleware.CorrelationID` returns an `http.Handler` — this has no effect on the call sites (`main.go`, the e2e tests' `httptest.NewServer`), since they only use this value through the interface. See [authentication.md](authentication.md) and [cross-cutting-concerns.md](cross-cutting-concerns.md) for details on the authentication/correlation-ID middleware.

In NestJS, `@Module({ providers: [...] })` would replace this wiring declaratively, and Nest's DI container resolves the graph at runtime. Go has no such container, so **`NewRouter` is the wiring code itself** — it lists out, in code, "this Handler is built with this Repository." As the wiring logic grows (as more domains are added), responsibility is delegated hierarchically from `main.go` to `NewRouter`, and again to the individual constructor calls inside `router.go` — but nowhere in this chain is there a container, reflection, or a decorator. It's all ordinary function calls. See [module-pattern.md](module-pattern.md) for details.

---

## Graceful Shutdown

Config (fail-fast validation), auth (JWT), secret (Secrets Manager), observability (structured logging), correlation ID, and graceful shutdown (`signal.NotifyContext` + `Shutdown(ctx)`) are all already reflected in the "Current code" above. See [graceful-shutdown.md](graceful-shutdown.md) for the details of signal handling and shutdown ordering — including the liveness/readiness endpoints (`/health/live`, `/health/ready`) that `router.go` registers, and the order in which `main()` calls `healthHandler.StartShutdown()` right after receiving SIGTERM.

---

## Principles

- **`main()` only does wiring** — it never contains business logic or conditional branching. It just calls constructors in order and starts the server.
- **Dependency assembly order = dependency direction**: lower layer (DB) → Infrastructure → Application → Interface → server start.
- **Constructor chaining instead of a DI container**: when a new dependency is needed, add a new constructor argument and fix up the call site — no reflection, no decorators.
- **Environment variable reads are all validated up front at bootstrap time** (see [config.md](config.md)) — `os.Getenv` is never scattered throughout Handler or domain code.

---

### Related documents

- [config.md](config.md) — fail-fast validation of environment variables
- [module-pattern.md](module-pattern.md) — details of how constructor chaining replaces NestJS modules/DI
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — assembling the middleware chain
- [graceful-shutdown.md](graceful-shutdown.md) — details of `signal.NotifyContext` + `Shutdown(ctx)`
- [container.md](container.md) — running the compiled binary as PID 1 in a container
