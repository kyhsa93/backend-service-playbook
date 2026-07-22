# Graceful Shutdown

The pattern for safely finishing in-flight requests before terminating, upon receiving SIGTERM in a container orchestration environment (Kubernetes, ECS, etc.).

## Current Implementation

The actual `src/main.ts` (see [bootstrap.md](bootstrap.md)) calls `enableShutdownHooks()` — this makes the `OnApplicationShutdown`/`BeforeApplicationShutdown` lifecycle hooks run upon receiving SIGTERM. The `ShutdownState` (`src/common/infrastructure/shutdown-state.ts`) and `HealthController` (`src/common/interface/health-controller.ts`, `GET /health/live`·`GET /health/ready`) defined in the "Health-check integration" section below are likewise reflected in `examples/` exactly as documented, registered in `src/app-module.ts`'s `providers`/`controllers`. The `RedisShutdown`/`QueueShutdown` patterns aren't applied yet since this repo has no Redis or message-queue connection of its own — they're left as extension patterns to add if ever needed.

## main.ts Configuration (Actual Code)

```typescript
// src/main.ts — an excerpt of the actual code; see bootstrap.md for the full file
import { NestFactory } from '@nestjs/core'

import { AppModule } from '@/app-module'

async function bootstrap() {
  const app = await NestFactory.create(AppModule)

  // ... see bootstrap.md for ValidationPipe, LoggingInterceptor, HttpExceptionFilter, CORS, Swagger setup

  // Graceful Shutdown — activates the NestJS lifecycle hooks on receiving SIGTERM/SIGINT
  app.enableShutdownHooks()

  await app.listen(process.env.PORT ?? 3000)
}

bootstrap()
```

The `OnApplicationShutdown` and `BeforeApplicationShutdown` lifecycle hooks only run if `app.enableShutdownHooks()` is called. Without this call, the hooks never run on SIGTERM and the process terminates immediately.

## Lifecycle Hooks

NestJS provides two shutdown lifecycle hooks. Pay attention to their execution order.

```
SIGTERM received
  → BeforeApplicationShutdown.beforeApplicationShutdown(signal)
    → wait for in-flight requests to finish, stop accepting new requests
  → HTTP server shuts down
  → OnApplicationShutdown.onApplicationShutdown(signal)
    → clean up resources: release DB connections, release message-broker connections, etc.
  → process exits
```

| Hook | Timing | Purpose |
|----|------|------|
| `BeforeApplicationShutdown` | Before the HTTP server shuts down | Wait for in-flight requests to finish, switch the health check to failing |
| `OnApplicationShutdown` | After the HTTP server shuts down | Release DB·Redis·message-broker connections |

## Cleaning Up Resources in the Infrastructure Layer

Implement resource-cleanup logic in the Infrastructure-layer module that manages that connection.

### Releasing the DB Connection

When using TypeORM, the TypeORM module releases the connection on its own, so no extra handling is needed. Only apply the pattern below when you're directly managing a custom DataSource.

```typescript
// src/common/infrastructure/database-shutdown.ts
import { Injectable, OnApplicationShutdown } from '@nestjs/common'
import { DataSource } from 'typeorm'

@Injectable()
export class DatabaseShutdown implements OnApplicationShutdown {
  constructor(private readonly dataSource: DataSource) {}

  async onApplicationShutdown() {
    if (this.dataSource.isInitialized) {
      await this.dataSource.destroy()
    }
  }
}
```

### Releasing the Redis Connection

```typescript
// src/common/infrastructure/redis-shutdown.ts
import { Injectable, OnApplicationShutdown } from '@nestjs/common'
import Redis from 'ioredis'

@Injectable()
export class RedisShutdown implements OnApplicationShutdown {
  constructor(private readonly redis: Redis) {}

  async onApplicationShutdown() {
    await this.redis.quit()
  }
}
```

### Releasing a Message Broker Connection (e.g. Bull Queue)

```typescript
// src/common/infrastructure/queue-shutdown.ts
import { Injectable, OnApplicationShutdown } from '@nestjs/common'
import { Queue } from 'bull'
import { InjectQueue } from '@nestjs/bull'

@Injectable()
export class QueueShutdown implements OnApplicationShutdown {
  constructor(@InjectQueue('task') private readonly queue: Queue) {}

  async onApplicationShutdown() {
    await this.queue.close()
  }
}
```

## Health-Check Integration

Switch the readiness state so the load balancer/orchestrator stops sending new traffic to an instance that's shutting down.

```typescript
// src/common/interface/health-controller.ts — actual code
import { Controller, Get, ServiceUnavailableException } from '@nestjs/common'
import { ApiOkResponse, ApiServiceUnavailableResponse, ApiTags } from '@nestjs/swagger'
import { SkipThrottle } from '@nestjs/throttler'

import { ShutdownState } from '@/common/infrastructure/shutdown-state'

@Controller('health')
@ApiTags('Health')
@SkipThrottle()
export class HealthController {
  constructor(private readonly shutdownState: ShutdownState) {}

  @Get('live')
  @ApiOkResponse({ description: '프로세스가 살아있음 — 종료 중에도 200을 반환한다' })
  live() {
    return { status: 'ok' }
  }

  @Get('ready')
  @ApiOkResponse({ description: '새 요청을 수락할 준비가 됨' })
  @ApiServiceUnavailableResponse({ description: '종료 절차가 시작되어 새 요청을 받지 않음' })
  ready() {
    if (this.shutdownState.isShuttingDown) {
      throw new ServiceUnavailableException('shutting down')
    }
    return { status: 'ok' }
  }
}
```

It throws a `ServiceUnavailableException` (`@nestjs/common`), not an `Error` — since the global `HttpExceptionFilter` (`src/common/http-exception.filter.ts`) passes an `HttpException` through as-is but converts a plain `Error` into a 500, a readiness failure must be an `HttpException` subtype to be correctly reported as a 503. Since the global `ThrottlerGuard` defined by [rate-limiting.md](rate-limiting.md) also applies to `/health/*`, `@SkipThrottle()` excludes the health check from rate limiting.

```typescript
// src/common/infrastructure/shutdown-state.ts — actual code
import { BeforeApplicationShutdown, Injectable } from '@nestjs/common'

@Injectable()
export class ShutdownState implements BeforeApplicationShutdown {
  private shuttingDown = false

  get isShuttingDown(): boolean {
    return this.shuttingDown
  }

  beforeApplicationShutdown() {
    this.shuttingDown = true
  }
}
```

As [shared-modules.md](shared-modules.md) explains, `common/` is a shared utility directory, not a separate module — instead of creating a new `@Global()` module to wrap `ShutdownState`, `ShutdownState`/`HealthController` are registered directly in `src/app-module.ts`'s `providers`/`controllers`, the same way as the existing `SecretService`. If another domain module later needs to inject it, promote it to a `@Global()` module at that point.

## Dockerfile / Container Integration

```dockerfile
# run node as PID 1 — receives SIGTERM directly
CMD ["node", "dist/main.js"]
```

- Using `npm run start:prod` inserts an npm process in between, delaying SIGTERM delivery.
- Run `node` directly so it receives SIGTERM immediately as PID 1.

### Kubernetes Integration

```yaml
# deployment.yaml
spec:
  terminationGracePeriodSeconds: 30
  containers:
    - name: app
      livenessProbe:
        httpGet:
          path: /health/live
          port: 3000
      readinessProbe:
        httpGet:
          path: /health/ready
          port: 3000
```

| Setting | Value | Description |
|------|---|------|
| `terminationGracePeriodSeconds` | 30 | The wait time from SIGTERM to SIGKILL. Set it with margin above the app's max request-processing time |
| `readinessProbe` | `/health/ready` | Returns 503 once ShutdownState switches to the shutting-down state → the load balancer blocks traffic |
| `livenessProbe` | `/health/live` | Confirms the process is alive. Returns 200 even while shutting down (to let in-flight requests finish) |

### Shutdown Flow Summary

```
1. Kubernetes sends SIGTERM
2. BeforeApplicationShutdown: ShutdownState.shuttingDown = true
3. readinessProbe fails → the load balancer blocks new traffic
4. In-flight requests finish processing
5. The HTTP server shuts down
6. OnApplicationShutdown: releases the DB·Redis·Queue connections
7. The process exits normally (exit code 0)
```

## Principles

- **`enableShutdownHooks()` is required**: always call it in main.ts. Without this call, the lifecycle hooks never run.
- **Clean up resources in `OnApplicationShutdown`**: safe because it runs after the HTTP server has shut down.
- **Switch readiness in `BeforeApplicationShutdown`**: stop accepting new requests before the HTTP server shuts down.
- **Use `node` directly in CMD**: receives SIGTERM immediately, with no npm/yarn wrapper in between.
- **Never throw an exception from shutdown logic**: if `onApplicationShutdown` throws, other modules' cleanup may not run. Wrap it in try-catch and just log.
