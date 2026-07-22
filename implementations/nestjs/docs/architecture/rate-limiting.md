# Rate Limiting

Uses `@nestjs/throttler` to limit the rate of API requests.

## Current Implementation

`@nestjs/throttler` is installed in `examples/`, and `src/app-module.ts` registers a 3-tier limit — short (default 3/second), medium (default 20/10 seconds), long (default 100/minute) — via `ThrottlerModule.forRoot(getThrottlerConfig())` exactly as shown in the "Global Configuration" code below, binding `ThrottlerGuard` as `APP_GUARD` — automatically applied to every endpoint. The thresholds are made overridable via `THROTTLE_*` environment variables by `src/config/throttle.config.ts` (see "Adjusting Production Values" below). The health-check endpoint (`src/common/interface/health-controller.ts`, see [graceful-shutdown.md](graceful-shutdown.md)) is excluded from the limit via `@SkipThrottle()`. This document's "Per-Endpoint Custom Limits" section is an extension pattern to add if it's ever needed, and hasn't been applied in `examples/` yet.

## Installation

```bash
npm install @nestjs/throttler
```

## Global Configuration

```typescript
// src/config/throttle.config.ts — actual code
import { ThrottlerModuleOptions } from '@nestjs/throttler'

function getEnvInt(name: string, defaultValue: number): number {
  const raw = process.env[name]
  if (!raw) return defaultValue
  const parsed = parseInt(raw, 10)
  return Number.isNaN(parsed) ? defaultValue : parsed
}

export function getThrottlerConfig(): ThrottlerModuleOptions {
  return {
    throttlers: [
      { name: 'short', ttl: getEnvInt('THROTTLE_SHORT_TTL_MS', 1000), limit: getEnvInt('THROTTLE_SHORT_LIMIT', 3) },
      { name: 'medium', ttl: getEnvInt('THROTTLE_MEDIUM_TTL_MS', 10000), limit: getEnvInt('THROTTLE_MEDIUM_LIMIT', 20) },
      { name: 'long', ttl: getEnvInt('THROTTLE_LONG_TTL_MS', 60000), limit: getEnvInt('THROTTLE_LONG_LIMIT', 100) }
    ]
  }
}
```

```typescript
// src/app-module.ts — actual code
import { ThrottlerModule, ThrottlerGuard } from '@nestjs/throttler'
import { APP_GUARD } from '@nestjs/core'
import { getThrottlerConfig } from '@/config/throttle.config'

@Module({
  imports: [
    ThrottlerModule.forRoot(getThrottlerConfig())
  ],
  providers: [
    { provide: APP_GUARD, useClass: ThrottlerGuard }
  ]
})
export class AppModule {}
```

Registering it as `APP_GUARD` automatically applies it to every endpoint. Multiple throttlers can be registered at once to layer short/medium/long-term limits.

If only `ThrottlerModule.forRoot()` is configured without actually applying the guard via `APP_GUARD` (or a controller's `@UseGuards(ThrottlerGuard)`) — i.e. it's defined but never applied, leaving it as dead code — `harness/evaluators/rules/rate-limiting.evaluator.ts` catches it as `rate-limiting.app-guard-missing`. It checks whether the guard is actually wired up inside the `@Module` decorator body or a controller's `@UseGuards()` (merely having the strings `APP_GUARD`/`ThrottlerGuard` somewhere in a file isn't enough to pass). `getThrottlerConfig()` is a pure function ([config.md](config.md)'s pattern) that bypasses `ConfigModule`, the same as `database.config.ts`/`app.config.ts` — since `ThrottlerModule.forRoot()` needs the value at the point the `AppModule`'s `imports` array is evaluated (before the DI container is assembled), injecting `ConfigService` isn't possible, so it uses the direct-call approach for the same reason as `data-source.ts`'s `getDatabaseUrl()`.

## Per-Endpoint Custom Limits (an Extension Pattern — Not Applied)

To apply a different limit to a specific endpoint, use the `@Throttle()` decorator.

```typescript
import { Throttle, SkipThrottle } from '@nestjs/throttler'

@Controller('orders')
export class OrderController {
  // limit only this endpoint to 5 requests per minute
  @Post()
  @Throttle({ long: { ttl: 60000, limit: 5 } })
  createOrder(@Body() body: CreateOrderRequestBody) { /* ... */ }

  // exclude the health check from the limit
  @Get('health')
  @SkipThrottle()
  health() { return { status: 'ok' } }
}
```

### Class-Level Exclusion

```typescript
// exclude the whole Controller from the limit
@SkipThrottle()
@Controller('internal')
export class InternalController { /* ... */ }
```

## Adjusting Production Values

To change a threshold (ttl/limit), instead of editing code and redeploying, set the environment variable below in the deployment environment and restart the process — if the value is absent, it falls back to `throttle.config.ts`'s defaults (identical to what used to be hardcoded: short 3/second, medium 20/10 seconds, long 100/minute).

| Environment variable | Default | Meaning |
|---|---|---|
| `THROTTLE_SHORT_TTL_MS` / `THROTTLE_SHORT_LIMIT` | `1000` / `3` | The 1-second window |
| `THROTTLE_MEDIUM_TTL_MS` / `THROTTLE_MEDIUM_LIMIT` | `10000` / `20` | The 10-second window |
| `THROTTLE_LONG_TTL_MS` / `THROTTLE_LONG_LIMIT` | `60000` / `100` | The 1-minute window |

Because these values are environment-variable-based, adjusting the threshold in production needs no code change or redeploy — the same as Go (`RATE_LIMIT_RPS`/`RATE_LIMIT_BURST`) and fastapi (`RATE_LIMIT_DEFAULT`/`RATE_LIMIT_WRITE`).

## Response Headers

The Throttler automatically includes rate-limit info in the response headers.

| Header | Description |
|------|------|
| `X-RateLimit-Limit` | The maximum number of allowed requests |
| `X-RateLimit-Remaining` | The number of remaining requests |
| `X-RateLimit-Reset` | The time remaining until the limit resets (seconds) |

Returns `429 Too Many Requests` when the limit is exceeded.

## Principles

- **Register it as a global Guard**: register ThrottlerGuard as `APP_GUARD` to apply a default limit to every endpoint.
- **Fine-tune per endpoint**: set a stricter limit on write APIs (POST, PUT, DELETE) than on read APIs.
- **Exclude internal endpoints**: exclude internal endpoints like health checks and metrics via `@SkipThrottle()`.
- **Manage limit values via environment variables**: don't hardcode them — make them adjustable at deploy time via `throttle.config.ts`'s `THROTTLE_*` environment variables (see "Adjusting Production Values" above).
