# App Bootstrap (Go)

Go-specific document — there's no corresponding document at the root (Go has no concept of a "framework bootstrap" corresponding to NestJS's `main.ts`/`NestFactory`). This document lays out how this repository's `cmd/server/main.go` actually assembles the application: structured logging with the correlation handler ([observability.md](observability.md)), tracing setup, config validation ([config.md](config.md)), three-BC dependency assembly, the Outbox and Task Queue background loops, and graceful shutdown ([graceful-shutdown.md](graceful-shutdown.md)). The middleware chain itself (the `func(http.Handler) http.Handler` chain presented in [cross-cutting-concerns.md](cross-cutting-concerns.md)) lives in `router.go`, not here.

---

## Current code — `cmd/server/main.go` (abridged)

The excerpt below quotes the real file; `// ... <what's elided> ...` comments mark where repetitive assembly blocks were shortened.

```go
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	_ "github.com/lib/pq"
	"golang.org/x/time/rate"

	// Blank-imported so its init() registers the generated OpenAPI spec with
	// swag's global registry (docs/architecture/api-documentation.md).
	_ "github.com/example/account-service/docs"

	"github.com/example/account-service/internal/application/command"
	"github.com/example/account-service/internal/application/event"
	integrationevent "github.com/example/account-service/internal/application/integration-event"
	"github.com/example/account-service/internal/config"
	// ... the internal/infrastructure imports: acl, auth, database, forecasting,
	// llm, logging, notification, outbox, persistence, scheduling, secret,
	// task-queue (as taskqueue), tracing ...
	httphandler "github.com/example/account-service/internal/interface/http"
	taskinterface "github.com/example/account-service/internal/interface/task"
)

// ... swag @-annotations (@title, @BasePath, @securityDefinitions.apikey) ...
func main() {
	// CorrelationHandler wraps JSONHandler so that, whenever the ctx carries a
	// correlation ID, it automatically adds a "correlation_id" field to every
	// log call (observability.md).
	jsonHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
	slog.SetDefault(slog.New(logging.NewCorrelationHandler(jsonHandler)))

	// Registers the process-wide TracerProvider + W3C traceparent propagator
	// (observability.md) — must happen before httphandler.NewRouter builds
	// the otelhttp-wrapped handler below. OTLPEndpoint() is empty in local
	// dev (NewTracerProvider falls back to a stdout exporter).
	shutdownTracing, err := tracing.NewTracerProvider(context.Background(), config.OTLPEndpoint())
	if err != nil {
		slog.Error("failed to set up tracing", "error", err)
		os.Exit(1)
	}

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
	defer func() {
		if closeErr := db.Close(); closeErr != nil {
			slog.Error("failed to close db", "error", closeErr)
		}
	}()

	// Dependency assembly — constructor chaining, no framework
	notifier := notification.NewService(notification.NewSESClient(), db)

	sqsConfig, err := config.LoadSQSConfig()
	// ... same config-error check as above ...

	outboxWriter := outbox.NewWriter()
	outboxPublisher := outbox.NewPublisher(db)
	sqsClient := outbox.NewSQSClient()

	// Only the composition root (main) knows about all three BCs
	// (Account/Card/Payment) — the BCs never import each other; they are
	// connected only via the Outbox's string event_type (cross-domain.md).
	accountRepo := persistence.NewAccountRepository(db, outboxWriter)
	transactionRepo := persistence.NewTransactionRepository(db)
	// dbManager satisfies the command.TransactionManager port — used only
	// when a Handler like TransferHandler needs to atomically group two or
	// more SaveAccount calls.
	dbManager := database.NewManager(db)
	cardRepo := persistence.NewCardRepository(db)
	credentialRepo := persistence.NewCredentialRepository(db)
	paymentRepo := persistence.NewPaymentRepository(db)
	// ... ACL adapters (acl.New*Adapter) and the cross-BC Command Handlers
	// (SuspendCardsByAccount, CancelCardsByAccount, WithdrawByPayment,
	// DepositByPayment) ...
	// ... LLM-backed Technical Services (llm.New*Impl) and the event
	// handlers they power: categorizeTransactionHandler,
	// detectWithdrawalAnomalyHandler, classifyRefundReasonHandler ...

	// This handlers map is used by outbox.Consumer to look up the
	// handler(s) for the eventType of a message received from SQS — Command
	// Handlers never reference this map at all (no synchronous draining,
	// domain-events.md). An eventType may have more than one subscriber.
	outboxHandlers := map[string][]outbox.Handler{
		"AccountCreated": {event.NewAccountCreatedEventHandler(notifier).Handle},
		"MoneyDeposited": {event.NewMoneyDepositedEventHandler(notifier).Handle},
		"MoneyWithdrawn": {
			event.NewMoneyWithdrawnEventHandler(notifier).Handle,
			categorizeTransactionHandler.Handle,
			detectWithdrawalAnomalyHandler.Handle,
		},
		// ... 13 more entries: the remaining Account/Payment Domain Events
		// (AccountSuspended, AccountReactivated, AccountClosed,
		// PaymentCompleted, PaymentCancelled, RefundApproved,
		// RefundRequested, InterestPaid) plus the Integration Events other
		// BCs subscribe to via unmarshal-glue closures (account.suspended.v1,
		// account.closed.v1, payment.completed.v1, payment.cancelled.v1,
		// refund.approved.v1) ...
	}

	outboxPoller := outbox.NewPoller(db, sqsClient, sqsConfig.QueueURL)
	outboxConsumer := outbox.NewConsumer(sqsClient, sqsConfig.QueueURL, outboxHandlers)

	// Task Queue dependencies — a separate table/queue from the
	// Domain/Integration Events (scheduling.md); only the low-level
	// sqsClient is shared with outbox.
	taskWriter := taskqueue.NewWriter(db)
	interestScheduler := scheduling.NewInterestScheduler(taskWriter)
	// ... statementScheduler, spendingAnalysisScheduler,
	// spendingForecastScheduler; the four Task Command Handlers; and the
	// Task Controllers that wrap them ...
	taskHandlers := map[string]taskqueue.Handler{
		"account.apply-interest":           interestTaskController.HandleApplyInterest,
		"card.send-usage-statement":        statementTaskController.HandleSendStatement,
		"account.analyze-monthly-spending": spendingAnalysisTaskController.HandleAnalyzeMonthlySpending,
		"account.forecast-spending":        spendingForecastTaskController.HandleForecastSpending,
	}
	taskPoller := taskqueue.NewPoller(db, sqsClient, sqsConfig.TaskQueueURL)
	taskConsumer := taskqueue.NewConsumer(sqsClient, sqsConfig.TaskQueueURL, taskHandlers)

	secretService := secret.NewService(secret.NewSecretsManagerClient(), 5*time.Minute)
	jwtSecret, err := config.LoadJWTSecret(context.Background(), secretService, os.Getenv("APP_ENV"))
	if err != nil {
		slog.Error("failed to load jwt secret", "error", err)
		os.Exit(1)
	}
	jwtService := auth.NewJWTService(jwtSecret, time.Hour)
	passwordHasher := auth.NewBcryptPasswordHasher()

	rateLimitConfig := config.LoadRateLimitConfig()
	limiter := rate.NewLimiter(rate.Limit(rateLimitConfig.RequestsPerSecond), rateLimitConfig.Burst)

	// ... nlTranslator/nlComposer — the two LLM Technical Services behind
	// AskTransactionHistoryHandler's structured-data RAG pipeline
	// (domain-service.md) ...

	mux, healthHandler := httphandler.NewRouter(accountRepo, cardRepo, credentialRepo, paymentRepo, accountAdapter, paymentCardAdapter, paymentAccountAdapter, jwtService, passwordHasher, nlTranslator, nlComposer, limiter, dbManager)

	srv := &http.Server{Addr: ":8080", Handler: mux}

	// ctx is cancelled on receiving SIGTERM/SIGINT — not just the HTTP
	// server, but also the Poller/Consumer background loops below are
	// stopped via this same ctx (graceful-shutdown.md).
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go func() {
		slog.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server error", "error", err)
			os.Exit(1)
		}
	}()

	// Outbox → SQS publishing (Poller) and SQS → EventHandler receiving
	// (Consumer) both run periodically and independently of HTTP requests —
	// Command Handlers never reference either of them at all (no synchronous
	// draining, domain-events.md). Both stop themselves when ctx is
	// cancelled.
	go outboxPoller.Run(ctx)
	go outboxConsumer.Run(ctx)

	// The Task Queue's Poller/Consumer are also independent background loops,
	// just like outbox. The Schedulers are themselves yet more independent
	// goroutines that create Tasks — they only enqueue and never execute
	// business logic (scheduling.md).
	go taskPoller.Run(ctx)
	go taskConsumer.Run(ctx)
	go interestScheduler.Run(ctx)
	go statementScheduler.Run(ctx)
	go spendingAnalysisScheduler.Run(ctx)
	go spendingForecastScheduler.Run(ctx)

	<-ctx.Done() // blocks until SIGTERM/SIGINT is received
	slog.Info("shutdown signal received")

	// Must be called before srv.Shutdown(ctx) — the orchestrator only cuts
	// off new traffic after readiness flips to 503 (graceful-shutdown.md).
	healthHandler.StartShutdown()

	// Set generously to match terminationGracePeriodSeconds (orchestrator setting).
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Waits for in-flight requests to finish while rejecting new connections.
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Error("graceful shutdown failed", "error", err)
	}

	// Flushes any spans still buffered in the TracerProvider's batcher — must
	// run after srv.Shutdown but before the process exits, or the last batch
	// is lost.
	if err := shutdownTracing(shutdownCtx); err != nil {
		slog.Error("tracer provider shutdown failed", "error", err)
	}

	slog.Info("server stopped")
	// defer db.Close() runs after this point — DB connections are cleaned up
	// only after the HTTP server is fully closed
}
```

One `main()` entirely replaces NestJS's `bootstrap()` function + `AppModule` composition + `NestFactory.create()`. With no framework, "bootstrap" isn't a special concept — it's just **one ordinary Go function running top to bottom in order**.

---

## Step-by-step breakdown

| Step | Code | Role |
|---|---|---|
| 0. Prepare logging/tracing/config | `slog.SetDefault(slog.New(logging.NewCorrelationHandler(jsonHandler)))`, `tracing.NewTracerProvider(...)`, `config.LoadDatabaseConfig()` | Sets up the correlation-aware JSON structured logger and the process-wide TracerProvider first, then validates required environment variables and calls `os.Exit(1)` on failure (see [config.md](config.md), [observability.md](observability.md)) |
| 1. Connect infrastructure | `sql.Open("postgres", dbConfig.URL)` | Creates the DB connection pool (the actual connection is lazy). `defer db.Close()` schedules cleanup for when `main()` exits |
| 2. Assemble Infrastructure | `notification.NewService(...)`, `outbox.NewWriter()`/`NewPublisher(db)`/`NewSQSClient()`, `database.NewManager(db)`, the `persistence.New*Repository(...)` constructors for all three BCs, the `acl.New*Adapter(...)` ACL adapters, the `llm.New*Impl(...)`/`forecasting.New*` Technical Services, `secret.NewService(...)`, `auth.NewJWTService(...)`/`NewBcryptPasswordHasher()`, `rate.NewLimiter(...)` | Injects the single `db`/`sqsClient` into multiple Infrastructure implementations — unlike NestJS's `@Global` DatabaseModule, it's just passing a variable as a function argument. The JWT secret is fetched by `config.LoadJWTSecret` from either an environment variable or Secrets Manager depending on the environment (APP_ENV) (see [secret-manager.md](secret-manager.md)) |
| 3. Assemble background pipelines | the `outboxHandlers` map (16 entries) + `outbox.NewPoller`/`NewConsumer`, the `taskHandlers` map (4 entries) + `taskqueue.NewPoller`/`NewConsumer`, the four `scheduling.New*Scheduler(taskWriter)` constructors | Two separate string-keyed routing maps: Domain/Integration Events (outbox) and Tasks (task queue). Command Handlers never reference either map (see [domain-events.md](domain-events.md), [scheduling.md](scheduling.md)) |
| 4. Assemble router/Handlers | `mux, healthHandler := httphandler.NewRouter(accountRepo, cardRepo, credentialRepo, paymentRepo, ..., jwtService, passwordHasher, nlTranslator, nlComposer, limiter, dbManager)` | Takes the Repositories/adapters/services and internally assembles the Command/Query Handlers, the HTTP handlers, and the middleware chain (see the "Key point" section below). Outbox publish/consume are unrelated to the Command Handler, so they're never passed to `NewRouter` |
| 5. Start the server | `go func() { srv.ListenAndServe() }()`, `go outboxPoller.Run(ctx)`, `go outboxConsumer.Run(ctx)`, `go taskPoller.Run(ctx)`, `go taskConsumer.Run(ctx)`, `go <each>Scheduler.Run(ctx)` | Each starts on its own independent goroutine, so `main()` isn't blocked and moves straight on to waiting for the signal (`<-ctx.Done()`). The HTTP server and the background loops know nothing of each other — they only share the same `ctx` for their stopping point |
| 6. Wait for the shutdown signal | `signal.NotifyContext` + `<-ctx.Done()` | Blocks until SIGTERM/SIGINT is received |
| 7. Graceful shutdown | `healthHandler.StartShutdown()` → `srv.Shutdown(shutdownCtx)` → `shutdownTracing(shutdownCtx)` | Fails readiness first so the orchestrator cuts off new traffic, then rejects new connections and waits for in-flight requests, then flushes buffered spans — see [graceful-shutdown.md](graceful-shutdown.md). The Poller/Consumer/Scheduler loops stop themselves the moment `ctx` is canceled (no separate Shutdown call needed) |

The dependency assembly order **exactly matches the dependency direction** — `db` (the lowest level) → Infrastructure (`notifier`/`outboxWriter`/`sqsClient`/Repositories/adapters/`jwtService`/...) → Handler (Application, inside `router.go`) → `mux` (Interface) → `http.Server`. Reordering any step breaks compilation (since it would reference a variable that doesn't exist yet) — the Go compiler itself enforces the assembly order.

---

## Key point — `router.go` stands in for the DI container's role

```go
// internal/interface/http/router.go — actual code (abridged)
func NewRouter(repo AccountStore, cardRepo card.Repository, credentialRepo credential.Repository, paymentStore PaymentStore, accountAdapter command.AccountAdapter, paymentCardAdapter command.PaymentCardAdapter, paymentAccountAdapter command.PaymentAccountAdapter, jwtService tokenService, passwordHasher command.PasswordHasher, nlTranslator query.NlTransactionQueryTranslator, nlComposer query.NlTransactionAnswerComposer, limiter *rate.Limiter, txManager command.TransactionManager) (http.Handler, *HealthHandler) {
	createAccountHandler := command.NewCreateAccountHandler(repo)
	depositHandler := command.NewDepositHandler(repo)
	// ... every remaining Account/Card/Payment/Auth Command/Query Handler is
	// created the same way via constructor calls — Outbox publish/consume are
	// unrelated to the Command Handler, so outboxPoller/outboxConsumer aren't
	// passed in here either ...

	accountHTTP := NewAccountHandler(createAccountHandler, depositHandler /* ... */)
	// ... cardHTTP, paymentHTTP, authHTTP assembled the same way ...
	healthHandler := NewHealthHandler()

	protected := http.NewServeMux()
	protected.HandleFunc("POST /accounts", accountHTTP.CreateAccount)
	// ... the remaining authenticated /accounts, /cards, /payments, /refunds endpoints ...

	// Routes subject to rate limiting
	limited := http.NewServeMux()
	limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
	// ... the remaining /accounts/, /cards(/), /payments(/), /refunds/ prefixes ...
	limited.HandleFunc("POST /auth/sign-up", authHTTP.SignUp)
	limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

	mux := http.NewServeMux()
	mux.Handle("/", middleware.RateLimit(limiter)(limited))
	// Health checks / Swagger UI / Prometheus scrape target — orchestrator- or
	// ops-facing, so not wrapped by the rate limit middleware.
	mux.HandleFunc("GET /health/live", healthHandler.Live)
	mux.HandleFunc("GET /health/ready", healthHandler.Ready)
	mux.Handle("GET /docs/", httpSwagger.WrapHandler)
	mux.Handle("GET /metrics", promhttp.Handler())

	instrumented := middleware.CorrelationID(middleware.RequestLogging(middleware.SecurityHeaders(middleware.Metrics(mux))))

	// otelhttp.NewHandler wraps everything, outermost — it starts (or
	// continues) a span for the whole request before the other middleware and
	// the actual handler ever run (observability.md).
	return otelhttp.NewHandler(instrumented, "account-service"), healthHandler
}
```

The first return value is an `http.Handler` rather than a `*http.ServeMux` because the outermost wrapper (`otelhttp.NewHandler`) returns an `http.Handler` — this has no effect on the call sites (`main.go`, the e2e tests' `httptest.NewServer`), since they only use this value through the interface. The second return value, `*HealthHandler`, is handed back so `main()` can call `healthHandler.StartShutdown()` on SIGTERM (see [graceful-shutdown.md](graceful-shutdown.md)). See [authentication.md](authentication.md) and [cross-cutting-concerns.md](cross-cutting-concerns.md) for details on the authentication/correlation-ID middleware, and [rate-limiting.md](rate-limiting.md) for the rate limiter.

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
