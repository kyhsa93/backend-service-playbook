# Observability (Go) — Structured Logging, Correlation ID

The principle follows the root [observability.md](../../../../docs/architecture/observability.md): JSON structured logs, snake_case field names, layer-based logging criteria (Domain never logs), and every log includes the correlation ID. Since the Go 1.21+ standard library's `log/slog` provides structured logging out of the box, no separate logging framework (the equivalent of Winston or Pino) is needed.

`main.go` and `notification/service.go` use `log/slog`-based structured logging.

---

## `log/slog` — actual implementation

```go
// internal/infrastructure/notification/service.go — actual code
import "log/slog"

func (s *Service) send(ctx context.Context, eventType string, content emailContent) error {
	output, err := s.sesClient.SendEmail(ctx, &ses.SendEmailInput{ /* ... */ })
	if err != nil {
		return fmt.Errorf("send email: %w", err)
	}

	messageID := aws.ToString(output.MessageId)
	if _, err := s.db.ExecContext(ctx, `INSERT INTO sent_emails (...) VALUES (...)`, /* ... */); err != nil {
		return fmt.Errorf("save sent email record: %w", err)
	}

	slog.InfoContext(ctx, "notification email sent",
		"event_type", eventType,
		"recipient", content.recipient,
		"ses_message_id", messageID,
	)
	return nil
}

// Notify returns a sending failure as an error rather than a log — the caller,
// handleMessage in outbox/consumer.go, receives this error, logs it via
// slog.ErrorContext, and doesn't delete the SQS message, so it gets redelivered
// after the visibility timeout (see domain-events.md).
func (s *Service) Notify(ctx context.Context, event account.DomainEvent) error {
	eventType, content, ok := describe(event)
	if !ok {
		return nil
	}
	if err := s.send(ctx, eventType, content); err != nil {
		return fmt.Errorf("notify %s: %w", eventType, err)
	}
	return nil
}
```

```go
// internal/infrastructure/outbox/consumer.go — actual code, logs the error Notify() returned
slog.ErrorContext(ctx, "event processing failed", "event_type", eventType, "error", err)
```

`slog.InfoContext`/`slog.ErrorContext` take a `ctx` — this connects naturally to the correlation ID propagation below. Fields are passed as key-value pairs, rendered by `slog` through either the default `TextHandler` or `JSONHandler`. Production uses `JSONHandler`:

```go
// cmd/server/main.go — actual code, set up once early during startup
slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
```

Example output (JSON, snake_case fields):

```json
{"time":"2026-07-06T10:00:00Z","level":"INFO","msg":"notification email sent","event_type":"MoneyDeposited","recipient":"a@example.com","ses_message_id":"010001..."}
```

Writing `slog`'s keys directly in snake_case as the root rule requires (`"event_type"`, `"ses_message_id"`) satisfies the rule with no separate conversion needed — Go's default convention is camelCase, but **since a log field key is just a string literal, it can be explicitly chosen in snake_case, breaking from convention.**

---

## Log level mapping

| Root level | `log/slog` equivalent |
|---|---|
| `error` | `slog.LevelError` / `slog.ErrorContext` |
| `warn` | `slog.LevelWarn` / `slog.WarnContext` |
| `log` (key business events) | `slog.LevelInfo` / `slog.InfoContext` |
| `debug` | `slog.LevelDebug` / `slog.DebugContext` |
| `verbose` | no separate level — either folded into `slog.LevelDebug`, or a custom level constant is defined (`const LevelVerbose = slog.Level(-8)`) |

In production, `HandlerOptions{Level: slog.LevelInfo}` filters out Debug and below. Per-environment levels are managed in the config struct from [config.md](config.md).

---

## Logging criteria by layer

| Layer | What gets logged | This repository's current state |
|---|---|---|
| `internal/domain/account/` | **Nothing is logged** | Actually contains no logging code — the principle is upheld. There's no `import "log"` |
| `internal/application/command,query/` | Business events, results of external calls | Currently no logging — there's room to add logs like `slog.InfoContext(ctx, "account created", "account_id", a.AccountID)` in a Handler |
| `internal/infrastructure/` | External integration failures/retries | `notification/service.go` (a success log for sending) and `outbox/poller.go`/`outbox/consumer.go` (failure logs for publishing/processing) all use `slog` |
| `internal/interface/http/` | Request errors | `writeAccountError` also leaves a server-side log on a 500 error (see below) |

The prohibition on logging in `internal/domain/` is automatically checked by `implementations/go/harness/no_logging_in_domain.go` (the `no-logging-in-domain` rule) — it flags FAIL if `internal/domain/**/*.go` imports `log`, `log/slog`, or any of the common third-party loggers (logrus/zap/zerolog).

An entirely empty `if err != nil { }` block that checks an error but neither logs nor returns it ("silently swallowing it") is caught by `implementations/go/harness/no_silent_catch.go` (the `no-silent-catch` rule) — errcheck (part of golangci-lint's default set) only catches the case where an error is never checked at all, so this rule fills the gap for the pattern where it's checked but discarded in an empty block.

`writeAccountError` in `account_handler.go` also leaves a server-side log when returning a 500 error to the client:

```go
// internal/interface/http/account_handler.go — actual code
func writeAccountError(w http.ResponseWriter, r *http.Request, err error) {
	switch {
	case errors.Is(err, account.ErrNotFound):
		http.Error(w, err.Error(), http.StatusNotFound)
	// ...
	default:
		slog.ErrorContext(r.Context(), "unhandled account error", "error", err)
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}
}
```

---

## Correlation ID — propagated via `context.Context`

The root document says to propagate it via context-local storage equivalent to AsyncLocalStorage (Node)/ThreadLocal (Java). Go's answer is the standard mechanism built exactly for this purpose — `context.Context` value propagation — with no separate library needed. Below is the actual code.

```go
// internal/interface/http/middleware/correlation_id_middleware.go — actual code (shared with cross-cutting-concerns.md)
func CorrelationID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		correlationID := r.Header.Get("X-Correlation-Id")
		if correlationID == "" {
			correlationID = uuid.NewString()
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

The key that holds the context value lives not in `internal/interface/http/middleware` but in `internal/common` (a framework-agnostic package any layer can reference) as `WithCorrelationID`/`CorrelationIDFromContext` — because the custom `slog.Handler` below also reads the value through functions in that same package.

## Wrapping `slog.Handler` to auto-add fields to every log

To avoid passing `"correlation_id", CorrelationIDFromContext(ctx)` at every single log call site, a custom handler wraps `slog.Handler` so that, whenever the `ctx` carries a correlation ID, it **automatically adds the field to every log record**:

```go
// internal/infrastructure/logging/correlation.go — actual code
package logging

type CorrelationHandler struct {
	slog.Handler
}

func NewCorrelationHandler(next slog.Handler) *CorrelationHandler {
	return &CorrelationHandler{Handler: next}
}

func (h *CorrelationHandler) Handle(ctx context.Context, record slog.Record) error {
	if correlationID := common.CorrelationIDFromContext(ctx); correlationID != "" {
		record.AddAttrs(slog.String("correlation_id", correlationID))
	}
	return h.Handler.Handle(ctx, record)
}

func (h *CorrelationHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithAttrs(attrs)}
}

func (h *CorrelationHandler) WithGroup(name string) slog.Handler {
	return &CorrelationHandler{Handler: h.Handler.WithGroup(name)}
}
```

In `main.go`'s logger initialization, the `JSONHandler` is wrapped with this handler:

```go
// cmd/server/main.go — actual code
jsonHandler := slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})
slog.SetDefault(slog.New(logging.NewCorrelationHandler(jsonHandler)))
```

From then on, even without passing `correlation_id` explicitly as in `slog.InfoContext(ctx, "account created", "account_id", a.AccountID)`, `CorrelationHandler.Handle` automatically adds the field whenever `ctx` carries a value. `WithAttrs`/`WithGroup` are wrapped too — otherwise, a sub-logger created via `slog.Logger.With(...)`/`WithGroup(...)` would lose automatic correlation_id injection.

---

## Metrics — Prometheus

`prometheus/client_golang` is the Go ecosystem's de facto standard, and is now a direct dependency (it used to be pulled in only transitively). `internal/interface/http/middleware/metrics_middleware.go` defines two package-level, `promauto`-registered collectors and a `Metrics` middleware that records both on every request:

```go
// internal/interface/http/middleware/metrics_middleware.go — actual code (summarized)
var (
	httpRequestsTotal = promauto.NewCounterVec(prometheus.CounterOpts{
		Name: "http_requests_total",
	}, []string{"method", "path", "status"})

	httpRequestDuration = promauto.NewHistogramVec(prometheus.HistogramOpts{
		Name:    "http_request_duration_seconds",
		Buckets: prometheus.DefBuckets,
	}, []string{"method", "path", "status"})
)

func Metrics(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)

		path := r.Pattern // net/http's Go 1.22+ matched-route pattern, not the raw URL path
		if path == "" {
			path = r.URL.Path
		}
		httpRequestsTotal.WithLabelValues(r.Method, path, strconv.Itoa(rec.status)).Inc()
		httpRequestDuration.WithLabelValues(r.Method, path, strconv.Itoa(rec.status)).Observe(time.Since(start).Seconds())
	})
}
```

The `path` label uses `r.Pattern` (the route pattern net/http's Go 1.22+ `ServeMux` matched, e.g. `"POST /accounts/{id}/deposit"`) rather than the raw URL path, so a path parameter like an account ID never blows up label cardinality. `Metrics` has to sit directly around the outermost `*http.ServeMux` (see `router.go`) for this to work — `RequireAuth` (`auth_middleware.go`) calls `r.WithContext`, which hands the rest of the chain a *copy* of the request, so anything wrapping further out than that copy point wouldn't see net/http's own `r.Pattern` mutation.

`router.go` serves the counters via the standard `promhttp.Handler()` on `GET /metrics` — unauthenticated and not rate-limited, the same treatment as the health-check routes, on the assumption (typical of most Prometheus deployments) that the scraper reaches this endpoint over a trusted, non-public network path.

---

## Tracing — OpenTelemetry

`go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp` (already an indirect dependency pulled in transitively by testcontainers-go; now promoted to direct) wraps the whole router:

```go
// internal/interface/http/router.go — actual code (summarized)
instrumented := middleware.CorrelationID(middleware.RequestLogging(middleware.SecurityHeaders(middleware.Metrics(mux))))
return otelhttp.NewHandler(instrumented, "account-service"), healthHandler
```

Wrapping outermost means every inner stage's `ctx` already carries an active span by the time it runs. `otelhttp.NewHandler` also, by default, extracts an incoming `traceparent` header (if the caller sent one) via the process-wide propagator, so a span it starts continues the caller's trace rather than starting a disconnected one.

### TracerProvider setup — stdout by default, OTLP in prod

`internal/infrastructure/tracing/provider.go`'s `NewTracerProvider` registers the process-wide `TracerProvider` + W3C `tracecontext` propagator once at startup (`main.go`), mirroring `config.LoadJWTSecret`'s "sane dev default, real value required in prod" shape (see [config.md](config.md)):

```go
// internal/infrastructure/tracing/provider.go — actual code (summarized)
func NewTracerProvider(ctx context.Context, otlpEndpoint string) (shutdown func(context.Context) error, err error) {
	exporter, err := newExporter(ctx, otlpEndpoint)
	// ...
	provider := sdktrace.NewTracerProvider(sdktrace.WithBatcher(exporter), sdktrace.WithResource(res))
	otel.SetTracerProvider(provider)
	otel.SetTextMapPropagator(propagation.TraceContext{})
	return provider.Shutdown, nil
}

func newExporter(ctx context.Context, otlpEndpoint string) (sdktrace.SpanExporter, error) {
	if otlpEndpoint == "" {
		return stdouttrace.New(stdouttrace.WithPrettyPrint()) // local dev — no real collector required
	}
	return otlptracehttp.New(ctx, otlptracehttp.WithEndpoint(otlpEndpoint)) // a real collector in staging/prod
}
```

`otlpEndpoint` comes from `config.OTLPEndpoint()`, which reads the standard `OTEL_EXPORTER_OTLP_ENDPOINT` env var (reused as-is rather than inventing a repo-specific name) — empty by default, so `go run`/`docker compose up` never needs a real OTLP collector running; set it to point at a real one (Jaeger/Tempo/a Datadog agent, etc.) in staging/production.

### `trace_id` in every log record

`infrastructure/logging.CorrelationHandler` (above) does double duty — besides `correlation_id`, it also adds `trace_id` whenever `ctx` carries an active span:

```go
// internal/infrastructure/logging/correlation.go — actual code (excerpt)
func (h *CorrelationHandler) Handle(ctx context.Context, record slog.Record) error {
	if correlationID := common.CorrelationIDFromContext(ctx); correlationID != "" {
		record.AddAttrs(slog.String("correlation_id", correlationID))
	}
	if spanContext := trace.SpanContextFromContext(ctx); spanContext.IsValid() {
		record.AddAttrs(slog.String("trace_id", spanContext.TraceID().String()))
	}
	return h.Handler.Handle(ctx, record)
}
```

This is the **same handler** that already injects `correlation_id` — there's deliberately no second, parallel mechanism for trace IDs.

### `traceparent` across the Outbox — one trace, HTTP request → async event processing

`internal/infrastructure/outbox/trace_context.go` extracts/re-hydrates the current span as a W3C `traceparent` string, the same idiom `docs/architecture/domain-events.md` and `scheduling.md` describe for the Outbox pattern generally:

- **`Writer.SaveAll`/`Publisher.Publish`** (writing a row): call `traceParentFromContext(ctx)` and store the result in a new `outbox.trace_parent` column (migration `0008_add_outbox_trace_parent.sql`).
- **`Poller.publish`**: forwards a non-empty `trace_parent` as the `"traceparent"` SQS message attribute — the same idiom already used for `"eventType"`.
- **`Consumer.handleMessage`**: reads the `"traceparent"` message attribute back out, re-hydrates it into `ctx` via `contextWithTraceParent`, then starts a new span (`tracer.Start(ctx, "outbox.consume "+eventType)`) as its child before invoking the registered `Handler`.

The result: an HTTP request that ends up writing (say) an `AccountCreated` row, and the Poller/Consumer round-trip that later processes it asynchronously, all show up under the same trace ID — and if that Handler in turn publishes a further Integration Event (e.g. `AccountSuspended` → `account.suspended.v1`), `Publisher.Publish` reads the same re-hydrated span back out of `ctx`, so the chain keeps extending across further async hops too.

This is scoped to the Domain/Integration Event Outbox specifically — the Task Queue (`internal/infrastructure/task-queue/`, see [scheduling.md](scheduling.md)) is driven by a Cron scheduler with no originating HTTP request to link to, so it isn't wired the same way.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — separation of responsibility by layer (the basis for logging placement)
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where the correlation-ID middleware sits
- [error-handling.md](error-handling.md) — the relationship between when an error is logged and the HTTP response
