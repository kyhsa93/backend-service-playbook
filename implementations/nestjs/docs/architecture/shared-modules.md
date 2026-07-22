# Shared Module Structure

Shared code that doesn't belong to any domain is placed at the paths below — based on the actual code:

```
src/
  common/                          # project-wide common utilities
    application/service/
      secret-service.ts            # the SecretService abstract class
    infrastructure/
      secret-service-impl.ts       # the Secrets Manager implementation (secret-manager.md)
    correlation-id-store.ts        # the AsyncLocalStorage-based store
    correlation-id.middleware.ts   # the Correlation ID injection middleware
    generate-error-response.ts
    generate-id.ts
    logging.interceptor.ts         # the request-logging interceptor
  config/                          # per-concern config (config.md)
    app.config.ts
    aws.config.ts
    database.config.ts
    jwt.config.ts
    notification.config.ts
    validation.config.ts
  database/                        # shared database code
    data-source.ts                 # the TypeORM DataSource (shared with the CLI migrations)
    transaction-manager.ts
    migrations/
  outbox/                          # the Outbox module (@Global)
    outbox-module.ts
    outbox.entity.ts
    outbox-writer.ts
    event-handler-registry.ts
  auth/                            # the authentication module (shared)
    auth-module.ts
    auth-service.ts                # issues/verifies tokens (JWT)
    auth.guard.ts                  # the Guard that extracts the Bearer token
    interface/
      auth-controller.ts           # POST /auth/sign-in, etc.
      dto/
  <domain>/                        # a domain module
    ...
```

- `src/common/` — framework-common code such as error handling, interceptors, Correlation ID, Secrets Manager. **There's no separate `@Global` module like a DatabaseModule, nor a `BaseEntity`-inheriting class** — `AccountEntity`/`OutboxEntity` each declare their own columns inline.
- `src/config/` — a config factory/helper function per concern (see [config.md](config.md))
- `src/database/` — the TypeORM `DataSource`, `TransactionManager`. `AppDataSource` is shared between the CLI migrations and the app (see [persistence.md](persistence.md))
- `src/outbox/` — `OutboxWriter`, `EventHandlerRegistry`, `OutboxPoller`, `OutboxConsumer`, `SqsClientProvider`. **Every domain (Account/Card/Payment, etc.) uses only this single shared module** — there's no per-domain `OutboxRelay`. `OutboxPoller` polls the `outbox` table and publishes to SQS, and `OutboxConsumer` long-polls SQS and routes it to `EventHandlerRegistry`. Each domain module registers its own Domain/Integration Event handlers into this single registry in its `onModuleInit()` (see [domain-events.md](domain-events.md)).
- `src/auth/` — the shared authentication/authorization module. Throws `UnauthorizedException()` directly, without a separate error-message enum (`auth-error-message.ts`).

> **Notification isn't here.** Since SES email sending (`NotificationService`) is an Account-only Technical Service used only by `AccountModule`, it lives inside the domain, as `src/account/application/service/notification-service.ts` (interface) + `src/account/infrastructure/notification/` (implementation·Entity) — see the Technical Service placement principle in [domain-service.md](../../../../docs/architecture/domain-service.md). If another domain (Card, etc.) later needs notifications too, decide then whether to promote it to a shared module (YAGNI) — don't move it to a shared location preemptively now.
