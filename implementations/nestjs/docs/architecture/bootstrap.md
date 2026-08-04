# App Bootstrap

> Below are the actual `src/main.ts` and `src/app-setup.ts`.

The app-object-level setup is extracted into `src/app-setup.ts`'s `configureApp(app)` and shared
between the production entrypoint and the E2E suite: `main.ts` calls it before serving Swagger
and listening, and every E2E spec applies it to the real `AppModule` (see
[testing.md](testing.md)). This keeps a single source of truth for the request pipeline —
production and the tests cannot drift apart. `main.ts` keeps only what makes no sense for a test
app: the Swagger document serving and `listen()`.

The first two imports are side-effect modules whose order is load-bearing, and both must stay
ahead of everything else. `@/config/timezone.config` pins the process timezone to UTC — timestamp columns are
`TIMESTAMP` (WITHOUT TIME ZONE) and the `pg` driver serializes a `Date` with the process's local
offset, so the pin is what decides which wall clock is stored, and Node only applies it to date
operations that happen after the assignment (see
[conventions.md](../conventions.md), "Timezone rule — store UTC"). `@/tracing` starts the
OpenTelemetry SDK, which stamps spans, so it comes second.

```typescript
// src/main.ts — actual code
// Must be the very first import in this file — see src/config/timezone.config.ts for why. It pins the
// process timezone to UTC so that a JS Date always serializes to the same wall clock in the
// `TIMESTAMP` (WITHOUT TIME ZONE) columns, and Node only applies that to date operations that
// happen after the assignment — so nothing that can stamp a time (starting with @/tracing) may
// be imported ahead of it.
import '@/config/timezone.config'

// Must be the first import after @/config/timezone.config — see src/tracing.ts for why (it patches Node's
// http module in place, and only requests made after it runs get instrumented).
import '@/tracing'

import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'

import { AppModule } from '@/app-module'
import { configureApp } from '@/app-setup'
import { getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // The shared app-object setup — helmet security headers, the global ValidationPipe, the
  // logging/metrics interceptors, the global exception filter, CORS, and enableShutdownHooks.
  // Lives in src/app-setup.ts so E2E tests apply the identical configuration to the real
  // AppModule instead of re-assembling their own.
  configureApp(app)

  // Swagger
  const swaggerConfig = new DocumentBuilder()
    .setTitle('Account Service API')
    .setDescription('API documentation for the DDD-based Account domain example service')
    .setVersion('0.1.0')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
  const swaggerDocument = SwaggerModule.createDocument(app, swaggerConfig)
  SwaggerModule.setup('docs', app, swaggerDocument)

  await app.listen(getPort())
}

bootstrap()
```

```typescript
// src/app-setup.ts — actual code (excerpt)
export function configureApp(app: INestApplication): void {
  // security headers, applied as early as possible — helmet's default CSP blocks Swagger UI's
  // inline scripts/styles, so /docs gets its own helmet instance with CSP turned off
  const defaultHelmet = helmet()
  const docsHelmet = helmet({ contentSecurityPolicy: false })
  app.use((req: Request, res: Response, next: NextFunction) => {
    const isSwaggerPath = req.path === '/docs' || req.path.startsWith('/docs/') || req.path === '/docs-json'
    const middleware = isSwaggerPath ? docsHelmet : defaultHelmet
    middleware(req, res, next)
  })

  // the global ValidationPipe — auto-applies class-validator, constructs a response with a code on failure
  app.useGlobalPipes(new ValidationPipe({
    whitelist: true,
    transform: true,
    exceptionFactory: (errors) => {
      const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
      return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
    }
  }))

  // the request-logging interceptor, plus the Prometheus HTTP-metrics interceptor
  app.useGlobalInterceptors(new LoggingInterceptor(), new MetricsInterceptor())

  // the global exception filter — converts even unhandled exceptions that aren't an HttpException into the standard error response format
  app.useGlobalFilters(new HttpExceptionFilter())

  // CORS
  app.enableCors({
    origin: getCorsOrigins(),
    methods: ['GET', 'POST', 'PATCH', 'PUT', 'DELETE', 'OPTIONS'],
    credentials: true
  })

  // Graceful Shutdown — runs the Nest lifecycle hooks (onModuleDestroy, etc.) on receiving SIGTERM/SIGINT
  app.enableShutdownHooks()
}
```

### Configuration Summary

| Setting | Where | Role |
|------|------|------|
| `import '@/config/timezone.config'` | `main.ts` | pins the process timezone to UTC — must be the first import, since Node applies the change only to date operations that follow it (see [conventions.md](../conventions.md), "Timezone rule — store UTC") |
| `import '@/tracing'` | `main.ts` | OpenTelemetry bootstrap — must be the first import after the timezone pin (see [observability.md](observability.md)) |
| the `logger` option | `main.ts` | excludes debug/verbose logs when `isProduction()` |
| `helmet` (two instances) | `app-setup.ts` | security headers everywhere; CSP relaxed only under `/docs` so Swagger UI keeps working (see [observability.md](observability.md)) |
| `ValidationPipe` | `app-setup.ts` | auto-applies class-validator decorators; `exceptionFactory` constructs a response with the `VALIDATION_FAILED` code (see [error-handling.md](error-handling.md)) |
| `LoggingInterceptor` | `app-setup.ts` | logs the request method/path/processing time |
| `MetricsInterceptor` | `app-setup.ts` | records `http_requests_total`/`http_request_duration_seconds` for `GET /metrics` (see [observability.md](observability.md)) |
| `HttpExceptionFilter` | `app-setup.ts` | the global exception filter. Serializes an `HttpException` in the standard format, and also converts any other unhandled exception (a plain `Error`, etc.) into `{ statusCode: 500, code: 'INTERNAL_ERROR', message, error }` instead of exposing the raw stack trace (see [error-handling.md](error-handling.md)) |
| `enableCors(...)` | `app-setup.ts` | `config/app.config.ts`'s `getCorsOrigins()` restricts allowed origins via the `CORS_ORIGIN` environment variable (comma-separated) in production (`isProduction()`), and returns allow-all (`true`) in every other environment |
| `DocumentBuilder` + `SwaggerModule` | `main.ts` | exposes the OpenAPI document at the `/docs` path. `addBearerAuth(..., 'token')` uses a name paired with the controller's `@ApiBearerAuth('token')` |
| `enableShutdownHooks()` | `app-setup.ts` | activates Nest lifecycle hooks like `OnApplicationShutdown` on receiving SIGTERM/SIGINT (see [graceful-shutdown.md](graceful-shutdown.md)) |

### Extension Points

- **Graceful Shutdown details**: `enableShutdownHooks()` is applied together with `HealthController`/`ShutdownState` (see [graceful-shutdown.md](graceful-shutdown.md)) — the `BeforeApplicationShutdown` hook first flips readiness to failing, then exposes it via `/health/live`·`/health/ready`. Cleaning up Redis·message-queue connections isn't applicable since this repo has no Redis or message queue. The TypeORM connection is cleaned up by Nest/TypeORM itself, so no separate handling is needed.
