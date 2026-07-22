# Rate Limiting (Go)

Go-specific document — there's no corresponding document at the root. The Go standard library has no built-in rate limiting like NestJS's `@nestjs/throttler` — the choice that best fits this repository's minimal-dependency principle is `golang.org/x/time/rate` (a token bucket), maintained directly by the Go team.

## Current implementation

`golang.org/x/time` has been added to `go.mod`, and the `RateLimit` middleware in `internal/interface/http/middleware/rate_limit_middleware.go` is applied globally to every API route — `/accounts`, `/auth/sign-in`, etc. — in `router.go`. `RateLimitConfig` in `internal/config/rate_limit.go` manages the thresholds via the `RATE_LIMIT_RPS`/`RATE_LIMIT_BURST` environment variables (defaults: 100 per second, burst 20), and `cmd/server/main.go` assembles a `rate.Limiter` with these values and injects it into `NewRouter`. `/health/live`/`/health/ready` are excluded by simply never wrapping them with the rate-limit middleware when their routes are registered.

---

## Dependency

```
go get golang.org/x/time/rate
```

`x/time` is a quasi-standard library maintained by the Go team — treated as "minimal and trustworthy third-party" at the same tier as `github.com/google/uuid` and `github.com/lib/pq`, which this repository already uses.

---

## Global middleware — token bucket

```go
// internal/interface/http/middleware/rate_limit_middleware.go — actual code
package middleware

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"golang.org/x/time/rate"
)

// rateLimitErrorResponse has the same field layout as the standard JSON error
// response {statusCode, code, message, error} required by root docs/architecture/error-handling.md.
type rateLimitErrorResponse struct {
	StatusCode int    `json:"statusCode"`
	Code       string `json:"code"`
	Message    string `json:"message"`
	Error      string `json:"error"`
}

// RateLimit is a token-bucket middleware that limits requests per second across the whole server.
// All requests share a single limiter, so it caps the throughput of the entire server, not any particular client.
func RateLimit(limiter *rate.Limiter) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if !limiter.Allow() {
				w.Header().Set("Retry-After", "1")
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusTooManyRequests)
				body := rateLimitErrorResponse{
					StatusCode: http.StatusTooManyRequests,
					Code:       "RATE_LIMIT_EXCEEDED",
					Message:    "too many requests",
					Error:      http.StatusText(http.StatusTooManyRequests),
				}
				if err := json.NewEncoder(w).Encode(body); err != nil {
					slog.ErrorContext(r.Context(), "failed to encode rate limit error response", "error", err)
				}
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
```

The reason `rateLimitErrorResponse` doesn't just import `ErrorResponse` from `internal/interface/http` directly is that if the `middleware` package imported that package, it would create a circular reference with `router.go` (`interface/http`) importing `middleware` — so the schema is simply duplicated with the same shape.

In `rate.NewLimiter(r, b)`, `r` is the number of tokens refilled per second (the average processing rate), and `b` is the bucket's maximum capacity (the instantaneous burst allowance). `limiter.Allow()` consumes one token and returns `true` if a token is available, or returns `false` immediately if not — it never blocks.

`limiter *rate.Limiter` is injected into `NewRouter` as a parameter — as [cross-cutting-concerns.md](cross-cutting-concerns.md) states, this repository chains middleware via direct nested calls (`A(B(h))`) with no separate `Chain()` helper, so `router.go` wraps it directly in the form `RateLimit(limiter)(mux)`.

```go
// internal/config/rate_limit.go — actual code
type RateLimitConfig struct {
	RequestsPerSecond float64 // the average allowed requests per second
	Burst             int     // the instantaneous burst size allowed
}

func LoadRateLimitConfig() RateLimitConfig {
	cfg := RateLimitConfig{RequestsPerSecond: 100, Burst: 20}
	if v := os.Getenv("RATE_LIMIT_RPS"); v != "" {
		if parsed, err := strconv.ParseFloat(v, 64); err == nil {
			cfg.RequestsPerSecond = parsed
		}
	}
	if v := os.Getenv("RATE_LIMIT_BURST"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.Burst = parsed
		}
	}
	return cfg
}
```

```go
// cmd/server/main.go — actual code (excerpt)
rateLimitConfig := config.LoadRateLimitConfig()
limiter := rate.NewLimiter(rate.Limit(rateLimitConfig.RequestsPerSecond), rateLimitConfig.Burst)

accountRepo := persistence.NewAccountRepository(db, outboxWriter)
mux, healthHandler := httphandler.NewRouter(accountRepo, jwtService, limiter)
```

```go
// internal/interface/http/router.go — actual code (excerpt)
limited := http.NewServeMux()
limited.Handle("/accounts", middleware.RequireAuth(jwtService)(protected))
limited.Handle("/accounts/", middleware.RequireAuth(jwtService)(protected))
limited.HandleFunc("POST /auth/sign-in", authHTTP.SignIn)

mux := http.NewServeMux()
mux.Handle("/", middleware.RateLimit(limiter)(limited))
// the healthcheck is for the orchestrator's probe only, so it isn't wrapped with the rate-limit middleware.
mux.HandleFunc("GET /health/live", healthHandler.Live)
mux.HandleFunc("GET /health/ready", healthHandler.Ready)

return middleware.CorrelationID(mux), healthHandler
```

The reason `limiter` is taken as a parameter to `NewRouter` is to separate the production value from the test value — `main()` passes a limiter built from `LoadRateLimitConfig()` (defaults: 100 per second, burst 20), while the e2e tests in `test/account_e2e_test.go` send dozens of requests within a short time in the same process, so they build and pass a much more generous limiter (`rate.NewLimiter(rate.Limit(100_000), 100_000)`) directly. This is the same reasoning and the same pattern as kotlin-springboot (overriding `resilience4j.ratelimiter.instances.http-write.limit-for-period` as a test property) and fastapi (overriding the `RATE_LIMIT_*` environment variables in the test conftest).

---

## Per-client (IP) limiting — a limiter map (not yet implemented, an extension option)

With only the currently implemented single global limiter, the entire server shares the same bucket instead of limiting any particular client. To limit per client, a separate `*rate.Limiter` per IP (or authenticated user ID) is kept and managed in a map — the idiom the official `golang.org/x/time/rate` documentation recommends. Below is a forward-looking design this repository hasn't adopted yet.

```go
// internal/interface/http/middleware/rate_limit_middleware.go
package middleware

import (
	"net"
	"net/http"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

type clientLimiter struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

// PerClientRateLimit applies an independent token bucket per client (remote IP).
type PerClientRateLimit struct {
	mu       sync.Mutex
	clients  map[string]*clientLimiter
	r        rate.Limit
	b        int
}

func NewPerClientRateLimit(r rate.Limit, b int) *PerClientRateLimit {
	rl := &PerClientRateLimit{clients: make(map[string]*clientLimiter), r: r, b: b}
	go rl.cleanupLoop() // periodically removes stale client entries so the map doesn't grow unbounded
	return rl
}

func (rl *PerClientRateLimit) getLimiter(key string) *rate.Limiter {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	c, ok := rl.clients[key]
	if !ok {
		c = &clientLimiter{limiter: rate.NewLimiter(rl.r, rl.b)}
		rl.clients[key] = c
	}
	c.lastSeen = time.Now()
	return c.limiter
}

func (rl *PerClientRateLimit) cleanupLoop() {
	for range time.Tick(time.Minute) {
		rl.mu.Lock()
		for key, c := range rl.clients {
			if time.Since(c.lastSeen) > 3*time.Minute {
				delete(rl.clients, key)
			}
		}
		rl.mu.Unlock()
	}
}

func (rl *PerClientRateLimit) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		if !rl.getLimiter(host).Allow() {
			// responds with the same standard JSON error schema as the real RateLimit middleware (see "Global middleware" above).
			http.Error(w, "too many requests", http.StatusTooManyRequests)
			return
		}
		next.ServeHTTP(w, r)
	})
}
```

`sync.Mutex` protects access to the map because multiple request goroutines call `getLimiter` concurrently — following the same principle as this repository's other concurrent code: "shared state is locked explicitly." Without `cleanupLoop`, the map would grow unbounded as unique clients keep accumulating, causing a memory leak.

---

## Per-endpoint differentiation (not yet implemented, an extension option)

Currently, every API route — `/accounts`, `/auth/sign-in`, etc. — shares the same global limiter. What corresponds to NestJS's `@Throttle()`/`@SkipThrottle()` decorators is, in Go, **wrapping each route with a different middleware** (see the middleware composition pattern in [cross-cutting-concerns.md](cross-cutting-concerns.md)) — below is a target design this repository hasn't adopted yet.

```go
// stricter limits for write endpoints (deposit/withdraw), excluding healthchecks
writeLimiter := rate.NewLimiter(rate.Limit(5), 2)
readLimiter := rate.NewLimiter(rate.Limit(50), 10)

mux.Handle("POST /accounts/{id}/deposit",
	middleware.RateLimit(writeLimiter)(http.HandlerFunc(accountHTTP.Deposit)))
mux.Handle("GET /accounts/{id}",
	middleware.RateLimit(readLimiter)(http.HandlerFunc(accountHTTP.GetAccount)))
mux.HandleFunc("GET /health/live", healthHandler.Live) // no rate limit applied — excluded from the orchestrator's probe
```

Instead of NestJS's structure of "a global Guard + decorators marking exceptions," this is **choosing and wrapping only the middleware wanted when registering each route** — with no decorators, but with the assembly explicit, so looking at just `router.go` shows exactly which route gets which limit.

---

## Response headers — must be filled in manually

`@nestjs/throttler` automatically fills in `X-RateLimit-*` headers, but `x/time/rate` has no such convenience feature — if needed, it's filled in directly in the middleware.

```go
if !limiter.Allow() {
	w.Header().Set("Retry-After", "1")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusTooManyRequests)
	// ... encode rateLimitErrorResponse to respond with the standard JSON error schema
	return
}
```

`limiter.Tokens()` can expose an approximation of the remaining token count, but providing an accurate `X-RateLimit-Remaining`/`X-RateLimit-Reset` would require adding a separate instrumentation layer on top of `rate.Limiter` — this repository doesn't cover that yet. The `Retry-After` header is filled in. Wrapping the 429 in the standard JSON error response schema (`statusCode`/`code`/`message`/`error`) was already covered in the "Global middleware" section above — it uses the same schema as [error-handling.md](error-handling.md).

---

## Adjusting production values

Setting the `RATE_LIMIT_RPS` (average allowed requests per second, default 100) and `RATE_LIMIT_BURST` (instantaneous burst size, default 20) environment variables in the deployment environment and restarting the process adjusts the thresholds with no code change or rebuild — because `cmd/server/main.go` reads these values via `config.LoadRateLimitConfig()` at startup to assemble the `rate.Limiter` (see the "Global middleware" section above). nestjs (`THROTTLE_*`) and fastapi (`RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE`) support the same kind of environment-variable-based adjustment, and java/kotlin-springboot achieve effectively equivalent deployment-time flexibility via `application.yml` + Spring profiles (see the "Adjusting production values" section in each language's document).

## Principles

- **Place it near the front of the middleware chain** — filtering before authentication wastes less computation (see the pipeline order in [cross-cutting-concerns.md](cross-cutting-concerns.md)). Confirmed: in `router.go`, `RateLimit` is applied outside (before) `RequireAuth`.
- **Exclude healthcheck/internal endpoints by simply never wrapping them with the middleware when their routes are registered.** Confirmed: `/health/live`/`/health/ready` are registered directly on `mux`, outside the sub-mux wrapped by `RateLimit`.
- **Manage thresholds via environment variables** (split into a `RateLimitConfig` struct, the same pattern as [config.md](config.md)). Confirmed: `LoadRateLimitConfig()` in `internal/config/rate_limit.go` reads `RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`. The same injection point is also used to separate the production default from the e2e test value (a generous limiter).
- **Distinguish global limiting from per-client limiting** — use a global limiter when the goal is protecting the server, and a per-client map when the goal is preventing client abuse. Currently only the global limiter is applied (the "Per-client limiting" section above is an unimplemented extension option).
- **Always clean up if a per-client map is introduced** — otherwise it becomes a memory leak.
- **Limit write endpoints more strictly than reads** — the "Per-endpoint differentiation" section above is an unimplemented extension option.

---

## Whether it's actually wired is automatically checked by the harness

A regression where the rate-limit middleware is only defined but never called from any assembly point like router.go/main.go (becoming dead code) is automatically checked by `implementations/go/harness/rate_limit_wired.go` (the `rate-limit-wired` rule) — it finds any function/method under `internal/interface/http/middleware/` whose name contains `RateLimit`, and confirms it's actually called somewhere in the remaining source, excluding the definition file and test files.

### Related documents

- [cross-cutting-concerns.md](cross-cutting-concerns.md) — the middleware chain composition pattern, pipeline order
- [error-handling.md](error-handling.md) — the standard JSON error schema the 429 response follows
- [config.md](config.md) — the pattern of splitting thresholds into an environment-variable-based config struct
- [observability.md](observability.md) — logging when a rate-limit rejection occurs
