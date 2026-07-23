# Logging / Observability

The pattern for applying structured logging on top of NestJS's built-in Logger.

## Declaring the Logger

Declare a Logger as a class field in every class. Pass the class name as the constructor argument to identify the log's source.

```typescript
import { Logger } from '@nestjs/common'

export class OrderCommandService {
  private readonly logger = new Logger(OrderCommandService.name)
}
```

## Log Level Policy

NestJS's Logger provides 5 levels. Use each level according to its intended purpose.

| Level | Method | Purpose | Example |
|------|--------|------|------|
| `error` | `logger.error()` | Request-processing failure, external system outage | DB connection failure, external API 5xx |
| `warn` | `logger.warn()` | Normal operation, but a situation that needs attention | Calling a deprecated endpoint, a retry occurring |
| `log` | `logger.log()` | Major business events, state changes | Order created, payment completed, app startup |
| `debug` | `logger.debug()` | Detailed info for development/debugging | Query parameters, intermediate computation results |
| `verbose` | `logger.verbose()` | Maximum detail | Full request/response payload |

### Per-Environment Log Level Configuration

```typescript
// src/main.ts
const app = await NestFactory.create(AppModule, {
  logger: process.env.NODE_ENV === 'production'
    ? ['error', 'warn', 'log']
    : ['error', 'warn', 'log', 'debug', 'verbose']
})
```

- **Production**: only outputs `error`, `warn`, `log`
- **Development/staging**: outputs every level

## Structured Logging

When integrating with an external monitoring system (Datadog, CloudWatch, etc.), use structured logs in JSON format.

### Field Naming Convention

Use **snake_case** for the field names of a log object.

```typescript
// a business event log
this.logger.log({ message: 'Order created', order_id: orderId, user_id: userId, amount })

// an error log
this.logger.error({ message: 'Failed to send to SQS', event_id: event.eventId, error })
```

### Per-Layer Logging Criteria

| Layer | What to log | Level |
|--------|----------|------|
| Interface (Controller) | Request errors (the catch block) | `error` |
| Application (Service) | Business events, results of external system calls | `log`, `error` |
| Infrastructure | External-integration failures/retries, query performance | `error`, `warn`, `debug` |
| Domain | No logging (framework-independent) | — |

The Domain layer never uses `Logger`. The result of domain logic is logged from the Application layer.

If `domain/*.ts` uses any of `@nestjs/common`'s `Logger`, `winston`, or `console.*`,
`harness/evaluators/rules/logging.evaluator.ts` catches it as `logging.no-logging-in-domain`.
Direct console usage (`logging.no-console`) and empty/unhandled catch blocks (`logging.no-empty-catch`,
`logging.no-swallowed-error`) are also verified by the same evaluator.

## Correlation ID — Request Tracing

When a single request passes through multiple services in a distributed environment, include the same Correlation ID in every log to trace it.

### CorrelationIdMiddleware

```typescript
// src/common/correlation-id.middleware.ts
import { Injectable, NestMiddleware } from '@nestjs/common'
import { Request, Response, NextFunction } from 'express'
import { randomUUID } from 'crypto'
import { trace } from '@opentelemetry/api'

import { CorrelationIdStore } from '@/common/correlation-id-store'

@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction) {
    const correlationId = (req.headers['x-correlation-id'] as string) ?? randomUUID()
    // @opentelemetry/instrumentation-http (src/tracing.ts) already started a span for this
    // request by the time middleware runs, so its trace ID is available here too.
    const traceId = trace.getActiveSpan()?.spanContext().traceId
    CorrelationIdStore.run({ correlationId, traceId }, () => {
      res.setHeader('x-correlation-id', correlationId)
      next()
    })
  }
}
```

### AsyncLocalStorage-Based Store

```typescript
// src/common/correlation-id-store.ts
import { AsyncLocalStorage } from 'async_hooks'

interface Store {
  correlationId: string
  traceId?: string  // see "Tracing" below — folded into the same store, not a parallel one
}

const storage = new AsyncLocalStorage<Store>()

export const CorrelationIdStore = {
  run: <T>(store: Store, fn: () => T): T => storage.run(store, fn),
  getId: () => storage.getStore()?.correlationId,
  getTraceId: () => storage.getStore()?.traceId
}
```

### Registering the Middleware

```typescript
// src/app-module.ts
import { MiddlewareConsumer, Module, NestModule } from '@nestjs/common'

import { CorrelationIdMiddleware } from '@/common/correlation-id.middleware'

@Module({ /* ... */ })
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer) {
    consumer.apply(CorrelationIdMiddleware).forRoutes('*')
  }
}
```

### Including the Correlation ID in Logs

```typescript
import { CorrelationIdStore } from '@/common/correlation-id-store'

this.logger.log({
  message: 'Order created',
  correlation_id: CorrelationIdStore.getId(),
  trace_id: CorrelationIdStore.getTraceId(),
  order_id: orderId
})
```

## LoggingInterceptor — HTTP Request/Response Logging

Automatically logs the method, URL, and response time of every HTTP request. Located at `src/common/logging.interceptor.ts`.

```typescript
// src/common/logging.interceptor.ts
import { CallHandler, ExecutionContext, Injectable, Logger, NestInterceptor } from '@nestjs/common'
import { Observable } from 'rxjs'
import { tap } from 'rxjs/operators'

import { CorrelationIdStore } from '@/common/correlation-id-store'

@Injectable()
export class LoggingInterceptor implements NestInterceptor {
  private readonly logger = new Logger('HTTP')

  intercept(context: ExecutionContext, next: CallHandler): Observable<any> {
    const req = context.switchToHttp().getRequest()
    const { method, url } = req
    const now = Date.now()

    return next.handle().pipe(
      tap(() => this.logger.log({
        message: `${method} ${url}`,
        method,
        url,
        duration_ms: Date.now() - now,
        correlation_id: CorrelationIdStore.getId(),
        trace_id: CorrelationIdStore.getTraceId()
      }))
    )
  }
}
```

Apply it globally by registering `app.useGlobalInterceptors(new LoggingInterceptor(), new MetricsInterceptor())` in [bootstrap.md](bootstrap.md), or at the Controller class level via `@UseInterceptors(LoggingInterceptor)`.

## Principles

- **Declare the Logger as a class field**: `new Logger(ClassName.name)` — automatically identifies the log's source.
- **No logging in the Domain layer**: the Domain stays framework-independent.
- **Use structured logs**: log as a JSON object for external monitoring integration, using snake_case field names.
- **Log errors with `logger.error()`**: always log the error in a catch block before throwing the exception.
- **Disable debug/verbose in production**: configure per-environment log levels to block unnecessary logs.
- **Trace requests with a Correlation ID**: in a distributed environment, include a Correlation ID in every log.

## Metrics

`GET /metrics` (`src/common/interface/metrics-controller.ts`) exposes Prometheus text-exposition
format via `prom-client`. `src/common/metrics-registry.ts` builds one explicit `Registry` (rather
than reaching for prom-client's implicit global `register`), registers `collectDefaultMetrics()`
(event-loop lag, heap/RSS, GC pauses — default Node.js process metrics), and defines two
HTTP-specific metrics:

- `http_requests_total` — a `Counter` labeled `method`, `route`, `status_code`
- `http_request_duration_seconds` — a `Histogram` with the same labels

`src/common/metrics.interceptor.ts` (`MetricsInterceptor`) records both on every request,
registered globally alongside `LoggingInterceptor`:

```typescript
// src/main.ts
app.useGlobalInterceptors(new LoggingInterceptor(), new MetricsInterceptor())
```

It reads `req.route.path` (not the raw URL) for the `route` label, keeping cardinality bounded
regardless of how many distinct IDs get requested (e.g. `/accounts/:accountId`, not
`/accounts/abc123...`).

`MetricsController` follows the same "non-domain-bearing common Controller" exception as
`HealthController` (`@SkipThrottle()`, `@Public()`, no Bounded Context of its own).

## Tracing

`src/tracing.ts` bootstraps a `NodeSDK` with `@opentelemetry/instrumentation-http` (HTTP
auto-instrumentation) — it must be the very first import in `main.ts`, before `@nestjs/core` or
anything that transitively pulls in `express`/`http`, since the instrumentation patches Node's
`http` module in place and only requests made after `NodeSDK#start()` runs get instrumented.

```typescript
// src/main.ts
import '@/tracing'  // must be the first import

import { NestFactory } from '@nestjs/core'
// ...
```

The trace exporter follows this repo's "sane dev default, real value required in prod" config
shape (`src/config/tracing.config.ts`, the same shape as `JWT_SECRET`/`AWS_ENDPOINT_URL`): with
no `OTEL_EXPORTER_OTLP_ENDPOINT` set, spans print to the console, so nothing ever needs a real
collector running for local dev or the test suite. Setting `OTEL_EXPORTER_OTLP_ENDPOINT` switches
to a real OTLP/HTTP exporter (Jaeger, Tempo, a Datadog agent, etc.) in staging/production.
`NodeSDK` also registers a W3C `TraceContext` propagator and an `AsyncLocalStorage`-based context
manager as the process-wide defaults.

### `trace_id` in logs

Rather than a parallel `AsyncLocalStorage`, `trace_id` is folded into the same
`CorrelationIdStore` the Correlation ID already uses — see the "AsyncLocalStorage-Based Store"
section above. `CorrelationIdMiddleware` captures `trace.getActiveSpan()?.spanContext().traceId`
once per request; `LoggingInterceptor` (and any other call site) reads it back with
`CorrelationIdStore.getTraceId()`.

### traceparent across the Outbox hop

An HTTP request and the async event processing it triggers (OutboxWriter → OutboxPoller → SQS →
OutboxConsumer, see [domain-events.md](domain-events.md)) show up as one trace:

- `src/outbox/trace-context.ts` — `traceParentFromContext()`/`contextWithTraceParent()` wrap
  `@opentelemetry/api`'s `propagation.inject`/`propagation.extract` around the process-wide W3C
  propagator `src/tracing.ts` registers.
- `OutboxWriter.saveAll()` captures `traceParentFromContext()` onto the `outbox` table's
  `traceParent` column when the row is written (once per batch — every event in one `saveAll()`
  call shares the same originating request/trace).
- `OutboxPoller` forwards a non-null `traceParent` as an SQS message attribute, the same way it
  already forwards `eventType`.
- `OutboxConsumer` reads the `traceparent` message attribute, re-hydrates it via
  `contextWithTraceParent()`, and runs the registered Handler inside
  `tracer.startActiveSpan('outbox.consume <eventType>', ...)` — so any span or log the Handler
  produces links back into the original trace. It also seeds a fresh `CorrelationIdStore` entry
  (`{ correlationId: generateId(), traceId }`) for the duration of that Handler call, since only
  the trace — not the original request's Correlation ID scope — crosses the Outbox hop.

A row with no `traceParent` (e.g. one written by a Task Queue batch job with nothing to trace)
leaves the Consumer's active context unchanged — `startActiveSpan` still works, it just starts a
new, disconnected trace rather than erroring.

## Alerting priorities

Top alerting items to wire up against these metrics/traces in production: DLQ depth > 0, SQS
`ApproximateAgeOfOldestMessage`, HTTP 5xx rate (`http_requests_total`), p99 latency
(`http_request_duration_seconds`), DB connection pool saturation.

## Security headers

`helmet` (`src/main.ts`) applies standard security headers (`X-Content-Type-Options`,
`X-Frame-Options`, `Strict-Transport-Security`, etc.) as the first middleware in the pipeline.
helmet's default Content-Security-Policy blocks Swagger UI's inline scripts/styles, so `/docs`
(and its static assets, `/docs/*`, plus `/docs-json`) gets a second `helmet` instance with
`contentSecurityPolicy: false` — every other header still applies there, only CSP is relaxed, and
only for that one path:

```typescript
// src/main.ts
const defaultHelmet = helmet()
const docsHelmet = helmet({ contentSecurityPolicy: false })
app.use((req, res, next) => {
  const isSwaggerPath = req.path === '/docs' || req.path.startsWith('/docs/') || req.path === '/docs-json'
  const middleware = isSwaggerPath ? docsHelmet : defaultHelmet
  middleware(req, res, next)
})
```
