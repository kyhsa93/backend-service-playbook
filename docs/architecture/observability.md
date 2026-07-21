# Observability — Logging, Metrics, Tracing

---

## Log-level policy

Define 5 levels and strictly stick to each level's intended use.

| Level | Use | Example |
|------|------|------|
| `error` | Request-handling failures, external system outages | DB connection failure, an external API returning 5xx, an unhandled exception |
| `warn` | Normal operation, but something that needs attention | A call to a deprecated endpoint, a retry occurring, approaching a threshold |
| `log` | Key business events, state changes | An order created, a payment completed, the app starting/stopping |
| `debug` | Detailed info for development/debugging | Query parameters, intermediate computed results |
| `verbose` | Maximum detail | The full request/response payload |

**Log level per environment:**

- **Production**: emit only `error`, `warn`, `log`
- **Development/staging**: emit every level

Unnecessary logging drives up operating costs and buries important logs in noise. Disable debug/verbose in production.

---

## Logging criteria per layer

| Layer | What to log | Level |
|--------|----------|------|
| Interface (Controller) | Request errors (in a catch block) | `error` |
| Application (Service) | Business events, results of external system calls | `log`, `error` |
| Infrastructure | External integration failures/retries, abnormal query performance | `error`, `warn`, `debug` |
| Domain | **Never logs** | — |

**Why the Domain layer never logs:** the Domain stays framework-independent. The result of domain logic is logged in the Application layer.

---

## Structured logging

When integrating with an external monitoring system (Datadog, CloudWatch, Grafana Loki, etc.), use structured JSON-formatted logs.

### Field-naming rule

Field names in a log object use **snake_case**.

```typescript
// a business-event log
logger.log({ message: 'Order created', order_id: orderId, user_id: userId, amount })

// an error log
logger.error({ message: 'SQS send failed', event_id: event.eventId, error })

// an HTTP request log
logger.log({ message: 'POST /orders', method: 'POST', url: '/orders', duration_ms: 42 })
```

**Why snake_case instead of camelCase:** most monitoring platforms (Datadog, CloudWatch) parse snake_case fields by default. A field-name mismatch breaks indexing or makes queries not work.

---

## Correlation ID — tracing distributed requests

To trace a single request across multiple services in logs, include a **Correlation ID** in every log entry.

### Flow

```
Client → sends the request with an x-correlation-id header (the server generates one if absent)
         → every log within the service includes correlation_id
         → the x-correlation-id header is forwarded on calls to other services
         → the response includes the x-correlation-id header
```

### Implementation principle

The Correlation ID is generated/extracted at the request entry point (the Interface layer) and propagated via **AsyncLocalStorage**. Every layer can access the current request's Correlation ID with no function argument needed.

```typescript
// conceptual — Correlation ID storage
const correlationStorage = new AsyncLocalStorage<string>()

// at request entry
const correlationId = request.headers['x-correlation-id'] ?? generateId()
correlationStorage.run(correlationId, () => handleRequest())

// when logging
const correlationId = correlationStorage.getStore()
logger.log({ message: '...', correlation_id: correlationId })
```

**See `docs/implementations/` for the per-framework implementation.**

---

## Metrics / tracing (directional notes)

This guide doesn't mandate a specific observability stack. Consider the following in production.

### Metrics

- A **Prometheus**-based `GET /metrics` endpoint + scraping
- Key alerting items:
  - HTTP 5xx rate
  - p99 response time
  - DB connection pool saturation
  - Message-queue DLQ depth > 0
  - Message queue's `ApproximateAgeOfOldestMessage`

### Tracing

- **OpenTelemetry** auto-instrumentation to automatically collect HTTP / DB / message-queue spans
- At an asynchronous boundary (Task Queue, Integration Event), including `traceparent` in the outbox payload propagates the trace context, linking the HTTP request → event processing into a single trace.
- Including `trace_id` in log records lets you jump from a trace to its logs.

---

## Principles

- **Never log in the Domain layer**: the result of domain logic is logged in the Application layer.
- **Use structured logs**: JSON + snake_case field names. Never use string interpolation.
- **Always log errors**: log the error in a catch block before rethrowing the exception. Never swallow it silently.
- **Trace requests with a Correlation ID**: include a Correlation ID in every log in a distributed environment.
- **Disable debug/verbose in production**: set log levels per environment.

---

### Related docs

- [layer-architecture.md](layer-architecture.md) — the separation of responsibilities per layer
- [cross-cutting-concerns.md](cross-cutting-concerns.md) — where Correlation ID injection happens
