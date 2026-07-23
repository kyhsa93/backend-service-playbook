# Cross-Cutting Concerns (Go)

The principle follows the root [cross-cutting-concerns.md](../../../../docs/architecture/cross-cutting-concerns.md): authentication, logging, input validation, and correlation ID are each handled at the pipeline stage matching their role, without mixing roles. Go has no dedicated decorator layer like NestJS's Guard/Pipe/Interceptor — the same role is implemented with `net/http`'s **middleware chain** (functions that wrap `func(http.Handler) http.Handler`).

---

## Request pipeline

```
Request → [0. otelhttp span] → [1. Correlation ID] → [2. Logging] → [3. Security headers] → [4. Metrics] → [5. Rate limit] → [6. Auth] → [7. Input validation] → [8. Handler] → Response
```

Go middleware works by chaining functions that wrap `http.Handler` in composition. Each middleware is split so it handles exactly one concern.

| Stage | Role | Go implementation location |
|------|------|------|
| 0. Tracing span | Starts (or continues, if the caller sent a `traceparent` header) an OpenTelemetry span for the whole request | `go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp`, wired in `router.go` ([observability.md](observability.md)) |
| 1. Correlation ID | Injects a tracing ID into every request | `interface/http/middleware/correlation_id_middleware.go` |
| 2. Response logging | Logs the request method/path/status code/duration | `interface/http/middleware/logging_middleware.go` (`RequestLogging`) |
| 3. Security headers | Sets `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`/(conditionally) `Strict-Transport-Security` on every response | `interface/http/middleware/security_headers_middleware.go` (`SecurityHeaders`) |
| 4. Metrics | Records `http_requests_total`/`http_request_duration_seconds` per request | `interface/http/middleware/metrics_middleware.go` (`Metrics`, [observability.md](observability.md)) |
| 5. Rate limit | Limits requests per second | `interface/http/middleware/rate_limit_middleware.go` ([rate-limiting.md](rate-limiting.md)) |
| 6. Authentication | Allows/rejects the request, injects user info into `context` | `interface/http/middleware/auth_middleware.go` ([authentication.md](authentication.md)) |
| 7. Input validation | JSON decoding, required-field checks | The entry point of the Handler (Go has no separate Pipe layer — see below) |
| 8. Handler | Calls the Command/Query Handler | `interface/http/account_handler.go` |

The reason `RequestLogging` sits outside (runs before) `RateLimit` is that even a request rejected with 429 needs to be logged — the actual chain in `router.go` is `otelhttp.NewHandler(CorrelationID(RequestLogging(SecurityHeaders(Metrics(mux)))), ...)`, and `mux` in turn routes through `RateLimit(limiter)(limited)`.

`Metrics` specifically has to sit directly around `mux` (not further out) — see its doc comment in `metrics_middleware.go` for why net/http's own request-pattern mutation requires that exact placement to label metrics by route rather than raw, unbounded-cardinality URL path.

---

## Middleware composition pattern — nested function calls

The Go standard library has no middleware-chaining helper. This repository doesn't have a separate `Chain()` helper either — instead, `router.go` nests middleware calls directly (in the form `A(B(h))`):

```go
// internal/interface/http/router.go — actual code (summarized)
limited := http.NewServeMux()
limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected)) // 6. authentication
limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)                  // no authentication needed

mux := http.NewServeMux()
mux.Handle("/", middleware.RateLimit(limiter)(limited)) // 5. rate limit
mux.HandleFunc("GET /health/live", healthHandler.Live)  // neither rate limit nor auth applied
mux.Handle("GET /metrics", promhttp.Handler())          // Prometheus scrape target — also unauthenticated/unlimited

// 3, 4. security headers + metrics sit directly around mux.
instrumented := middleware.CorrelationID(middleware.RequestLogging(middleware.SecurityHeaders(middleware.Metrics(mux))))

// 0. otelhttp wraps everything, outermost, so every inner stage's ctx
// already carries an active span.
return otelhttp.NewHandler(instrumented, "account-service"), healthHandler
```

With only a handful of middleware, this amount of nesting reads fine — if the count grows, introducing a composition helper in the form `Chain(h, A, B, C)` should be considered.

---

## Correlation ID injection (preprocessing stage)

```go
// internal/interface/http/middleware/correlation_id_middleware.go — actual code
package middleware

import (
	"context"
	"net/http"

	"github.com/google/uuid"

	"github.com/example/account-service/internal/common"
)

func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString() // the server generates one if the client didn't send one
		}
		ctx := common.WithCorrelationID(r.Context(), correlationID)
		w.Header().Set("X-Correlation-Id", correlationID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func CorrelationIDFromContext(ctx context.Context) string {
	return common.CorrelationIDFromContext(ctx)
}
```

The key that holds the context value (`correlationIDKey`) lives in `internal/common` (a framework-agnostic package any layer can reference) — because `internal/infrastructure/logging.CorrelationHandler` (see [observability.md](observability.md) below) also needs to read it via the same key.

The role Node.js's `AsyncLocalStorage` used to play is replaced in Go by **`context.Context` value propagation** — since Go explicitly passes `ctx` on every function call, instead of hidden storage (ALS), the `context.Context` passed as an argument carries the correlation ID, cancellation signal, and deadline together. See [observability.md](observability.md) for detailed usage.

---

## Authentication (middleware stage)

Token verification and user-info extraction finish before the Handler is reached. See [authentication.md](authentication.md) for the detailed implementation.

---

## Security headers (preprocessing stage)

```go
// internal/interface/http/middleware/security_headers_middleware.go — actual code (summarized)
func SecurityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "strict-origin-when-cross-origin")
		if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
			w.Header().Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
		}
		next.ServeHTTP(w, r)
	})
}
```

Unlike `RequireAuth`/`RateLimit`, there's no per-route opt-out — these headers are cheap and safe on every response, including health checks and the Swagger UI. `Strict-Transport-Security` is the one exception: it's only meaningful (and only set) once the request has actually reached the app over TLS — either `r.TLS != nil` (Go's own `net/http` server terminating TLS directly) or the `X-Forwarded-Proto: https` header a TLS-terminating reverse proxy/load balancer sets on the plain-HTTP request it forwards downstream (this app's assumed production deployment shape — see [container.md](container.md)/[graceful-shutdown.md](graceful-shutdown.md)).

---

## Input validation — Go has no separate Pipe layer

The Go standard library has no automatic validation layer like NestJS's `ValidationPipe` + `class-validator` decorators. This repository **validates explicitly at the entry point of the Handler**.

```go
// internal/interface/http/account_handler.go
func (h *AccountHandler) CreateAccount(w http.ResponseWriter, r *http.Request) {
	var body CreateAccountRequest
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest) // format validation failure → 400
		return
	}
	if !isValidEmail(body.Email) {
		http.Error(w, "email must be a valid, non-empty email address", http.StatusBadRequest)
		return
	}
	// only once this passes does execution reach the Handler (business logic)
	a, err := h.createAccount.Handle(r.Context(), command.CreateAccountCommand{ /* ... */ })
	// ...
}
```

- **Formal validation** (JSON parse failure, email format) is blocked immediately with a 400 early in the pipeline — the top of the Handler in this repository — matching the root principle.
- **Business rule validation** (e.g. attempting to deposit into an already-suspended account) is handled in the Domain layer (inside `Account.Deposit()`). `writeAccountError` distinguishes these and maps the status code accordingly (see [error-handling.md](error-handling.md)).
- If the validation items grow, they can be extracted into a validation-only file like `internal/interface/http/validate.go` to keep Handler functions short. A tag-based validation library like `go-playground/validator` would give declarative validation similar to NestJS's `class-validator`, but this repository prioritizes handling it with the standard library alone (`isValidEmail` is an example of this).

---

## HTTP request logging (post-response stage)

Beyond logging individual domain events (a success log for sending in `notification/service.go`, and failure logs for publishing/processing in `outbox/poller.go`/`outbox/consumer.go` — see [observability.md](observability.md)), this repository also has response-logging middleware that consistently records the method/path/status code/duration for every request:

```go
// internal/interface/http/middleware/logging_middleware.go — actual code (summarized)
package middleware

import (
	"log/slog"
	"net/http"
	"time"
)

// statusRecorder wraps http.ResponseWriter to observe the status code the handler actually wrote.
type statusRecorder struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
}

func (rec *statusRecorder) WriteHeader(status int) {
	rec.status = status
	rec.wroteHeader = true
	rec.ResponseWriter.WriteHeader(status)
}

func RequestLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		slog.InfoContext(r.Context(), "http request handled",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration_ms", time.Since(start).Milliseconds(),
		)
	})
}
```

The reason the `correlation_id` field isn't passed explicitly here is that `internal/infrastructure/logging.CorrelationHandler`, which `main.go` wraps around `slog`'s default logger, automatically adds whatever value is carried on `ctx` to every log record (see [observability.md](observability.md)) — `RequestLogging` must be registered inside (running later than) `CorrelationID` to inherit that ctx (`middleware.CorrelationID(middleware.RequestLogging(middleware.SecurityHeaders(middleware.Metrics(mux))))` in `router.go`).

The request log is never written individually inside a Handler — logging is done consistently for every route by a single middleware. See [observability.md](observability.md) for structured logging details.

---

## Cross-cutting concerns are prohibited in the Domain layer

```go
// forbidden — using logger/HTTP concepts in the Domain layer
package account

import "log/slog" // ← forbidden

func (a *Account) Cancel(reason string) error {
	slog.Info("account cancelled") // ← forbidden — Domain must know nothing about any logging framework
	// ...
}
```

The actual code in the `internal/domain/account/` package imports only standard-library base packages such as `errors`, `time` — it doesn't even import `log`, `net/http`, or `context`. A Domain method only takes values and returns a value or an error — it never involves itself with any cross-cutting concern.

---

## Principles

- **Use the middleware suited to the role**: separate authentication/logging/correlation ID into their own distinct middleware functions.
- **Middleware is applied to a route group**: it is never reimplemented inside an individual handler.
- **Keep the Handler pure**: it is responsible only for calling the Command/Query Handler and converting the request/response.
- **Propagate via `context.Context`**: correlation ID, auth info, etc. are passed to the next layer as context values.

---

### Related documents

- [authentication.md](authentication.md) — details of the authentication middleware
- [observability.md](observability.md) — structured logging, correlation ID propagation
- [error-handling.md](error-handling.md) — where errors are converted into HTTP status codes
