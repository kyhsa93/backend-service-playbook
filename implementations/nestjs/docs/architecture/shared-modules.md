# Shared Module Structure

Shared code that doesn't belong to any domain is placed at the paths below — based on the actual code:

```
src/
  common/                          # project-wide common utilities
    common-module.ts               # @Global module exporting SecretService
    application/service/
      secret-service.ts            # the SecretService abstract class
    infrastructure/
      secret-service-impl.ts       # the Secrets Manager implementation (secret-manager.md)
      shutdown-state.ts            # graceful-shutdown readiness flag
    interface/
      health-controller.ts         # GET /health, /health/readiness, /health/liveness
      metrics-controller.ts        # GET /metrics
      dto/
        error-response-body.ts     # the shared error-response schema
    correlation-id-store.ts        # the AsyncLocalStorage-based store
    correlation-id.middleware.ts   # the Correlation ID injection middleware
    user-context-store.ts          # the authenticated-user AsyncLocalStorage store
    user-context.interceptor.ts
    generate-error-response.ts
    generate-id.ts
    http-exception.filter.ts       # the global exception filter
    logging.interceptor.ts         # the request-logging interceptor
    metrics-registry.ts
    metrics.interceptor.ts
  config/                          # per-concern config (config.md)
    app.config.ts
    aws.config.ts
    database.config.ts
    jwt.config.ts
    llm.config.ts
    notification.config.ts
    throttle.config.ts
    tracing.config.ts
    validation.config.ts
  database/                        # shared database code
    base.entity.ts                 # BaseEntity — createdAt/updatedAt/deletedAt columns
    data-source.ts                 # the TypeORM DataSource (shared with the CLI migrations)
    transaction-manager.ts
    migrations/
  outbox/                          # the Domain Event Outbox module (@Global)
    outbox-module.ts
    outbox.entity.ts
    outbox-writer.ts
    outbox-poller.ts
    outbox-consumer.ts
    event-handler-registry.ts
    sqs-client-provider.ts         # the shared SQS_CLIENT provider
    trace-context.ts
  task-queue/                      # the Task Queue module (@Global) — see scheduling.md
    ...
  auth/                            # the authentication module (shared)
    auth-module.ts
    auth-service.ts                # issues/verifies tokens (JWT)
    auth.guard.ts                  # the Guard that extracts the Bearer token
    auth-error-code.ts
    auth-error-message.ts
    authenticated.decorator.ts
    public.decorator.ts
    domain/ · application/ · infrastructure/
    interface/
      auth-controller.ts           # POST /auth/sign-in, etc.
      dto/
  <domain>/                        # a domain module
    ...
```

- `src/common/` — framework-common code such as error handling, interceptors, Correlation ID, UserContextStore, health/metrics endpoints, Secrets Manager. `CommonModule` is `@Global` and exports `SecretService` so any domain module can inject it.
- **Three `@Global` modules exist** — `CommonModule`, `OutboxModule`, and `TaskQueueModule`. Cross-cutting infrastructure that every domain needs is a legitimate use of `@Global`; keep the set small and never make a domain module `@Global`.
- `src/config/` — a config factory/helper function per concern (see [config.md](config.md))
- `src/database/` — the TypeORM `DataSource`, `TransactionManager`, and `BaseEntity` (the shared `createdAt`/`updatedAt`/`deletedAt` columns). Domain persistence Entities (`AccountEntity`, `CardEntity`, `PaymentEntity`, etc.) extend `BaseEntity`; framework bookkeeping tables (`OutboxEntity`, `TaskOutboxEntity`) do not — they are not soft-deletable domain records. `AppDataSource` is shared between the CLI migrations and the app (see [persistence.md](persistence.md))
- `src/outbox/` — `OutboxWriter`, `EventHandlerRegistry`, `OutboxPoller`, `OutboxConsumer`, `sqs-client-provider.ts` (`SQS_CLIENT`). **Every domain (Account/Card/Payment, etc.) uses only this single shared module** — there's no per-domain `OutboxRelay`. `OutboxPoller` polls the `outbox` table and publishes to SQS, and `OutboxConsumer` long-polls SQS and routes it to `EventHandlerRegistry`. Each domain module registers its own Domain/Integration Event handlers into this single registry in its `onModuleInit()` (see [domain-events.md](domain-events.md)).
- `src/auth/` — the shared authentication/authorization module. It follows the same error-handling convention as the domains: `auth-error-message.ts` + `auth-error-code.ts`, mapped to HTTP exceptions in the Controller via `generateErrorResponse` (see [error-handling.md](error-handling.md)).

> **Notification isn't here.** Since SES email sending (`NotificationService`) is an Account-only Technical Service used only by `AccountModule`, it lives inside the domain, as `src/account/application/service/notification-service.ts` (interface) + `src/account/infrastructure/notification/` (implementation·Entity) — see the Technical Service placement principle in [domain-service.md](../../../../docs/architecture/domain-service.md). If another domain (Card, etc.) later needs notifications too, decide then whether to promote it to a shared module (YAGNI) — don't move it to a shared location preemptively now.
