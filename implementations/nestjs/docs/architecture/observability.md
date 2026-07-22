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
this.logger.log({ message: '주문 생성 완료', order_id: orderId, user_id: userId, amount })

// an error log
this.logger.error({ message: 'SQS 전송 실패', event_id: event.eventId, error })
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

import { CorrelationIdStore } from '@/common/correlation-id-store'

@Injectable()
export class CorrelationIdMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction) {
    const correlationId = (req.headers['x-correlation-id'] as string) ?? randomUUID()
    CorrelationIdStore.run(correlationId, () => {
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

const storage = new AsyncLocalStorage<string>()

export const CorrelationIdStore = {
  run: (id: string, fn: () => void) => storage.run(id, fn),
  getId: () => storage.getStore()
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
  message: '주문 생성 완료',
  correlation_id: CorrelationIdStore.getId(),
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
        correlation_id: CorrelationIdStore.getId()
      }))
    )
  }
}
```

Apply it globally by registering `app.useGlobalInterceptors(new LoggingInterceptor())` in [bootstrap.md](bootstrap.md), or at the Controller class level via `@UseInterceptors(LoggingInterceptor)`.

## Principles

- **Declare the Logger as a class field**: `new Logger(ClassName.name)` — automatically identifies the log's source.
- **No logging in the Domain layer**: the Domain stays framework-independent.
- **Use structured logs**: log as a JSON object for external monitoring integration, using snake_case field names.
- **Log errors with `logger.error()`**: always log the error in a catch block before throwing the exception.
- **Disable debug/verbose in production**: configure per-environment log levels to block unnecessary logs.
- **Trace requests with a Correlation ID**: in a distributed environment, include a Correlation ID in every log.

## Metrics / Tracing (Notes)

This guide doesn't mandate a specific observability stack. Consider the following in production.

- **Metrics**: typically Prometheus (a `/metrics` endpoint + scraping). In NestJS, integrate via a package like `@willsoto/nestjs-prometheus`.
- **Tracing**: use OpenTelemetry auto-instrumentation to automatically collect HTTP/TypeORM/SQS spans. When using a Task Queue, carrying `traceparent` in `task_outbox` and propagating the context at the Task boundary binds the HTTP request → Task processing into a single trace.
- **Top alerting priorities**: DLQ depth > 0, SQS `ApproximateAgeOfOldestMessage`, HTTP 5xx rate, p99 latency, DB connection pool saturation.
- **Log ↔ trace correlation**: include `trace_id` in log records to enable jumping from a trace to its logs.

Follow each stack's official documentation and your team's conventions for the concrete implementation.
