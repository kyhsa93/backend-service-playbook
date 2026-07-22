# Graceful Shutdown (Go)

The principle follows the root [graceful-shutdown.md](../../../../docs/architecture/graceful-shutdown.md): on receiving SIGTERM, flip readiness to failing first, let in-flight requests finish, close the HTTP server, and only then clean up resources. The Go standard library implements this entire flow without a framework, using just `signal.NotifyContext` and `http.Server.Shutdown(ctx)`.

`cmd/server/main.go` actually uses this pattern — below is the real code.

---

## Actual implementation — `signal.NotifyContext` + `Shutdown(ctx)`

The rest of `main()`'s assembly (config validation, DB, Infrastructure, `outboxPoller`/`outboxConsumer`, `NewRouter(accountRepo, jwtService, limiter)`, etc.) is exactly the code shown in [bootstrap.md](bootstrap.md); only the server start/stop portion is shown below:

```go
// cmd/server/main.go — actual code (excerpt of the final part)
srv := &http.Server{Addr: ":8080", Handler: mux}

// ctx is canceled on receiving SIGTERM/SIGINT.
ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
defer stop()

go func() {
	slog.Info("listening", "addr", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		slog.Error("server error", "error", err)
		os.Exit(1)
	}
}()

<-ctx.Done() // blocks until SIGTERM is received
slog.Info("shutdown signal received")

// set with margin to match terminationGracePeriodSeconds (e.g. same 30s as the orchestrator's setting)
shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
defer cancel()

// waits for in-flight requests to finish while rejecting new connections.
if err := srv.Shutdown(shutdownCtx); err != nil {
	slog.Error("graceful shutdown failed", "error", err)
}
slog.Info("server stopped")
// defer db.Close() runs after this point — DB connections are cleaned up only after the HTTP server has fully closed
```

**How the flow maps to the root principle's steps:**

| Root step | Go implementation |
|---|---|
| 1. Receive SIGTERM | `signal.NotifyContext(ctx, syscall.SIGTERM, syscall.SIGINT)` — converts the signal into context cancellation |
| 2. Flip readiness to 503 | `healthHandler.StartShutdown()` sets an `atomic.Bool` flag, and the `/health/ready` handler checks it and returns 503 — see the healthcheck section below |
| 3. Wait for in-flight requests to finish | `srv.Shutdown(shutdownCtx)` handles this automatically — it rejects new connections immediately and waits for existing connections to go idle |
| 4. Close the HTTP server | The point at which `Shutdown()` returns |
| 5. Clean up resources | `defer db.Close()` runs when `main()` exits (i.e. after `Shutdown()` returns) |
| 6. Normal exit | When `main()` returns normally, exit code 0 |

---

## Liveness / readiness endpoints

`HealthHandler` in `internal/interface/http/health_handler.go` provides `/health/live` and `/health/ready`, and `router.go` registers these two routes directly on `mux` without wrapping them in the rate-limit middleware ([rate-limiting.md](rate-limiting.md)).

```go
// internal/interface/http/health_handler.go — actual code
type HealthHandler struct {
	shuttingDown atomic.Bool
}

func NewHealthHandler() *HealthHandler { return &HealthHandler{} }

func (h *HealthHandler) StartShutdown() { h.shuttingDown.Store(true) }

func (h *HealthHandler) Live(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK) // always 200 — the process is alive even while shutting down
}

func (h *HealthHandler) Ready(w http.ResponseWriter, r *http.Request) {
	if h.shuttingDown.Load() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}
```

`NewRouter` returns the `*HealthHandler` it assembled, and `main()` holds onto it to use right after receiving the signal:

```go
// cmd/server/main.go — actual code (excerpt)
accountRepo := persistence.NewAccountRepository(db, outboxWriter)
mux, healthHandler := httphandler.NewRouter(accountRepo, jwtService, limiter)

// ...

<-ctx.Done() // blocks until SIGTERM/SIGINT is received
slog.Info("shutdown signal received")

// must be called before srv.Shutdown(ctx) — the orchestrator only cuts new traffic
// after readiness flips to 503, so readiness must fail before the HTTP server actually
// stops in order to get a zero-downtime transition.
healthHandler.StartShutdown()

shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
defer cancel()
if err := srv.Shutdown(shutdownCtx); err != nil {
	slog.Error("graceful shutdown failed", "error", err)
}
```

The key is calling `healthHandler.StartShutdown()` **before** `srv.Shutdown()` — following the order the root document emphasizes (readiness flips before the HTTP server closes) ensures there's no gap between the moment the orchestrator sees `/health/ready`'s 503 and cuts new traffic routing, and the moment the server actually starts rejecting new connections.

---

## Running the process directly in the container

```dockerfile
# correct — run the Go binary directly as PID 1 (see container.md)
CMD ["/app/server"]
```

A Go binary needs no shell script or launcher like `npm`/`yarn` in the first place — running the compiled single executable directly via the exec form of `CMD` makes that process PID 1, so it receives SIGTERM immediately with no delay. The "wrapper process swallows the signal" problem that affects Node/npm simply doesn't exist in Go.

---

## `terminationGracePeriodSeconds`

The 30-second value in `context.WithTimeout(context.Background(), 30*time.Second)` must match the orchestrator's (Kubernetes, etc.) `terminationGracePeriodSeconds` — if the Go-side timeout is longer than the orchestrator's grace period, SIGKILL arrives and force-terminates the process while graceful shutdown is still in progress.

---

### Related documents

- [container.md](container.md) — Dockerfile CMD configuration, exposing healthcheck endpoints
- [observability.md](observability.md) — structured logging during shutdown
- [scheduling.md](scheduling.md) — graceful shutdown of the background worker (Ticker)
- [rate-limiting.md](rate-limiting.md) — the routing approach that excludes `/health/live`/`/health/ready` from the rate-limit middleware
