# NestJS Implementation

## Overview

[NestJS](https://nestjs.com/) is a TypeScript-based Node.js server framework with a built-in module system and DI container.
The concrete guide and runnable examples implementing this playbook's principles with NestJS live in `implementations/nestjs/` in this repo.

**→ [implementations/nestjs/CLAUDE.md](../../implementations/nestjs/CLAUDE.md)** — entry point for the detailed NestJS implementation guide
**→ [implementations/nestjs/examples/](../../implementations/nestjs/examples/)** — full implementation example of the three Account/Card/Payment Bounded Contexts
**→ [implementations/nestjs/harness/](../../implementations/nestjs/harness/)** — automated evaluator that checks guide compliance

---

## NestJS-specific implementation coverage

| Principle doc (root, shared) | NestJS implementation doc |
|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | `implementations/nestjs/docs/architecture/layer-architecture.md` — @Injectable, @Module, DI binding |
| [directory-structure.md](../architecture/directory-structure.md) | `implementations/nestjs/docs/architecture/directory-structure.md` — 4-layer directory layout, file naming |
| [aggregate-id.md](../architecture/aggregate-id.md) | `implementations/nestjs/docs/architecture/aggregate-id.md` — generateId(), 32-char hyphen-free hex |
| [repository-pattern.md](../architecture/repository-pattern.md) | `implementations/nestjs/docs/architecture/repository-pattern.md` — TypeORM, @InjectRepository, NestJS DI wiring |
| [persistence.md](../architecture/persistence.md) | `implementations/nestjs/docs/architecture/persistence.md` — TypeORM QueryBuilder, TransactionManager (AsyncLocalStorage), migrations |
| [domain-events.md](../architecture/domain-events.md) | `implementations/nestjs/docs/architecture/domain-events.md` — @HandleEvent, OutboxWriter, OutboxPoller, OutboxConsumer, SQS |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | `implementations/nestjs/docs/architecture/cqrs-pattern.md` — @nestjs/cqrs, CommandBus, QueryBus |
| [error-handling.md](../architecture/error-handling.md) | `implementations/nestjs/docs/architecture/error-handling.md` — generateErrorResponse, HttpExceptionFilter |
| [api-response.md](../architecture/api-response.md) | `implementations/nestjs/docs/architecture/api-response.md` — page/take DTO, class-validator |
| [authentication.md](../architecture/authentication.md) | `implementations/nestjs/docs/architecture/authentication.md` — JWT, AuthGuard |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | `implementations/nestjs/docs/architecture/cross-cutting-concerns.md` — Middleware, Guard, Interceptor, Pipe |
| [scheduling.md](../architecture/scheduling.md) | `implementations/nestjs/docs/architecture/scheduling.md` — @Cron, SQS Task Queue, idempotency |
| [observability.md](../architecture/observability.md) | `implementations/nestjs/docs/architecture/observability.md` — structured log, Correlation ID, Prometheus `/metrics` (prom-client), OpenTelemetry HTTP tracing + traceparent across the Outbox hop, helmet security headers |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | `implementations/nestjs/docs/architecture/graceful-shutdown.md` — enableShutdownHooks |
| [container.md](../architecture/container.md) | `implementations/nestjs/docs/architecture/container.md` — multi-stage build |
| [config.md](../architecture/config.md) | `implementations/nestjs/docs/architecture/config.md` — ConfigModule, class-validator env-var validation |
| [secret-manager.md](../architecture/secret-manager.md) | `implementations/nestjs/docs/architecture/secret-manager.md` — SecretsManagerClient, TTL cache |
| [local-dev.md](../architecture/local-dev.md) | `implementations/nestjs/docs/architecture/local-dev.md` — docker-compose, LocalStack |
| [file-storage.md](../architecture/file-storage.md) | `implementations/nestjs/docs/architecture/file-storage.md` — StorageService, presigned URL, S3 |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | `implementations/nestjs/docs/architecture/tactical-ddd.md` — real code for Money (Value Object), Account (Aggregate Root) |
| [domain-service.md](../architecture/domain-service.md) | Directly cited in the root doc as a real working cross-Aggregate example — `RefundEligibilityService` (`examples/src/payment/domain/refund-eligibility-service.ts`) is responsible for a judgment that coordinates the two Aggregates `Payment` and `Refund`. There is no separate nestjs-specific doc (see footnote below). |
| [testing.md](../architecture/testing.md) | `implementations/nestjs/docs/architecture/testing.md` — jest, SQLite in-memory / testcontainers E2E |
| [conventions.md](../conventions.md) | `implementations/nestjs/docs/conventions.md` — file naming, import rules, TypeScript typing patterns |
| — (NestJS-specific, no corresponding root doc) | `implementations/nestjs/docs/architecture/module-pattern.md` — @Module, providers, exports, circular dependencies |
| — (NestJS-specific) | `implementations/nestjs/docs/architecture/bootstrap.md` — main.ts, NestFactory, Swagger |
| — (NestJS-specific) | `implementations/nestjs/docs/architecture/shared-modules.md` — shared module structure |
| — (NestJS-specific) | `implementations/nestjs/docs/architecture/design-principles.md` — summary of core design principles |
| — (NestJS-specific) | `implementations/nestjs/docs/architecture/cross-domain.md` — Adapter pattern implementation details (see [cross-domain-communication.md](../architecture/cross-domain-communication.md) for the principle) |

The NestJS-specific versions of `cross-domain-communication.md` and `strategic-ddd.md` were removed since they were pure duplicates of the root docs — they just reference the root docs directly. `domain-service.md` likewise has no separate NestJS-specific doc, but as the table row indicates, the root doc itself now cites nestjs's real code (a grounded reference, not a pure duplicate).

---

## Why NestJS

- TypeScript-native — type safety, with a decorator-based DI container built in
- The module system maps naturally onto Bounded Context boundaries (`1 BC = 1 NestJS Module`)
- Abstract-class-based DI tokens suit the Repository pattern (dependency inversion) well
- The `@nestjs/cqrs` package lets you optionally apply the CQRS Handler pattern
