# App Bootstrap

> Below is the actual `src/main.ts`.

```typescript
// src/main.ts — actual code
import '@/tracing'  // must be the first import — see observability.md

import { BadRequestException, ValidationPipe } from '@nestjs/common'
import { NestFactory } from '@nestjs/core'
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger'
import { NextFunction, Request, Response } from 'express'
import helmet from 'helmet'

import { AppModule } from '@/app-module'
import { HttpExceptionFilter } from '@/common/http-exception.filter'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { MetricsInterceptor } from '@/common/metrics.interceptor'
import { getCorsOrigins, getPort, isProduction } from '@/config/app.config'

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, {
    logger: isProduction()
      ? ['error', 'warn', 'log']
      : ['error', 'warn', 'log', 'debug', 'verbose']
  })

  // security headers, applied as early as possible — see observability.md for why /docs gets
  // its own helmet instance
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

  // Swagger
  const swaggerConfig = new DocumentBuilder()
    .setTitle('Account Service API')
    .setDescription('API documentation for the DDD-based Account domain example service')
    .setVersion('0.1.0')
    .addBearerAuth({ type: 'http', scheme: 'bearer', bearerFormat: 'JWT' }, 'token')
    .build()
  const swaggerDocument = SwaggerModule.createDocument(app, swaggerConfig)
  SwaggerModule.setup('docs', app, swaggerDocument)

  // Graceful Shutdown — runs the Nest lifecycle hooks (onModuleDestroy, etc.) on receiving SIGTERM/SIGINT
  app.enableShutdownHooks()

  await app.listen(getPort())
}

bootstrap()
```

### Configuration Summary

| Setting | Role |
|------|------|
| `import '@/tracing'` | OpenTelemetry bootstrap — must be the first import (see [observability.md](observability.md)) |
| the `logger` option | excludes debug/verbose logs when `isProduction()` |
| `helmet` (two instances) | security headers everywhere; CSP relaxed only under `/docs` so Swagger UI keeps working (see [observability.md](observability.md)) |
| `ValidationPipe` | auto-applies class-validator decorators; `exceptionFactory` constructs a response with the `VALIDATION_FAILED` code (see [error-handling.md](error-handling.md)) |
| `LoggingInterceptor` | logs the request method/path/processing time |
| `MetricsInterceptor` | records `http_requests_total`/`http_request_duration_seconds` for `GET /metrics` (see [observability.md](observability.md)) |
| `HttpExceptionFilter` | the global exception filter. Serializes an `HttpException` in the standard format, and also converts any other unhandled exception (a plain `Error`, etc.) into `{ statusCode: 500, code: 'INTERNAL_ERROR', message, error }` instead of exposing the raw stack trace (see [error-handling.md](error-handling.md)) |
| `enableCors(...)` | `config/app.config.ts`'s `getCorsOrigins()` restricts allowed origins via the `CORS_ORIGIN` environment variable (comma-separated) in production (`isProduction()`), and returns allow-all (`true`) in every other environment |
| `DocumentBuilder` + `SwaggerModule` | exposes the OpenAPI document at the `/docs` path. `addBearerAuth(..., 'token')` uses a name paired with the controller's `@ApiBearerAuth('token')` |
| `enableShutdownHooks()` | activates Nest lifecycle hooks like `OnApplicationShutdown` on receiving SIGTERM/SIGINT (see [graceful-shutdown.md](graceful-shutdown.md)) |

### Extension Points

- **Graceful Shutdown details**: `enableShutdownHooks()` is applied together with `HealthController`/`ShutdownState` (see [graceful-shutdown.md](graceful-shutdown.md)) — the `BeforeApplicationShutdown` hook first flips readiness to failing, then exposes it via `/health/live`·`/health/ready`. Cleaning up Redis·message-queue connections isn't applicable since this repo has no Redis or message queue. The TypeORM connection is cleaned up by Nest/TypeORM itself, so no separate handling is needed.
