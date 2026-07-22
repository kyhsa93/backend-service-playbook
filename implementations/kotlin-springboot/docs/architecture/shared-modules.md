# Shared Code Placement — Kotlin Spring Boot

## Current state — `common/`/`config/`/`auth/`/`secret/`/`outbox/`/`notification/`/`taskqueue/` all exist

Checking `examples/src/main/kotlin/com/example/accountservice/` shows the current tree has `account/` (1st domain), `card/` (2nd domain), `payment/` (3rd domain — Payment/Refund, `RefundEligibilityService`), plus **seven shared packages** (`common/`, `config/`, `auth/`, `secret/`, `outbox/`, `notification/`, `taskqueue/`), all present.

```
com.example.accountservice/
  AccountServiceApplication.kt
  common/            ← CorrelationIdFilter/RequestLoggingInterceptor/WebConfig/GenerateId (cross-cutting-concerns.md, aggregate-id.md)
  config/            ← AwsProperties/SesProperties/SqsProperties (config.md)
  auth/               ← AuthService/JwtAuthenticationFilter/SecurityConfig + the Credential Aggregate (authentication.md)
  secret/             ← SecretService/SecretServiceImpl/SecretsEnvironmentPostProcessor (secret-manager.md)
  outbox/             ← OutboxEvent/OutboxWriter/OutboxPoller/OutboxConsumer/EventHandlerRegistry (domain-events.md)
  notification/       ← NotificationService/NotificationServiceImpl/SesConfig/SentEmail (shared by both the Account+Card BCs)
  taskqueue/          ← TaskOutbox/TaskQueue/TaskOutboxWriter/TaskOutboxPoller/TaskQueueConsumer/TaskHandlerRegistry/SchedulingConfig (scheduling.md)
  account/           ← Bounded Context (1st domain)
  card/              ← Bounded Context (2nd domain)
  payment/           ← Bounded Context (3rd domain — Payment/Refund, RefundEligibilityService)
```

The "Placement of shared infrastructure" section of [directory-structure.md](directory-structure.md) already states this current state. This document expands on that table, laying out **why each piece of shared code is placed in that package** and **what might be added next**.

## Actual package placement

```
com.example.accountservice/
  AccountServiceApplication.kt

  common/                          ← project-wide common utilities (not a domain) — actual code
    CorrelationIdFilter.kt           ← Filter (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt     ← HandlerInterceptor (cross-cutting-concerns.md)
    WebConfig.kt                     ← @Configuration, registers the Interceptor
    GenerateId.kt                    ← Aggregate ID generation util (aggregate-id.md)

  config/                          ← the collection of @ConfigurationProperties data classes — actual code
    AwsProperties.kt                  ← prefix="aws", @Validated (config.md)
    SesProperties.kt                  ← prefix="ses", @Validated
    SqsProperties.kt                  ← prefix="sqs", @Validated — domainEventQueueUrl (standard queue) + taskQueueUrl (FIFO queue)

  auth/                            ← the shared authentication module (used by multiple BCs together) — actual code
    application/AuthService.kt        ← token issuance (authentication.md)
    application/command/SignUpService.kt / SignInService.kt   ← the sign-up/sign-in use cases
    application/query/CredentialQuery.kt        ← the Credential read-only port
    application/service/PasswordHasher.kt       ← a Technical Service interface
    domain/Credential.kt                        ← Aggregate — the only shared package with its own domain/
    domain/CredentialRepository.kt              ← the Credential write-only port
    infrastructure/JwtAuthenticationFilter.kt   ← the Bearer-token-verifying Filter
    infrastructure/SecurityConfig.kt            ← @Configuration, whitelisted paths
    infrastructure/BCryptPasswordHasher.kt      ← the PasswordHasher implementation
    infrastructure/CredentialRepositoryImpl.kt  ← the CredentialRepository + CredentialQuery implementation
    interfaces/rest/AuthController.kt           ← the sign-up/sign-in endpoints

  secret/                          ← Secrets Manager integration — actual code
    application/service/SecretService.kt        ← interface (secret-manager.md)
    infrastructure/SecretServiceImpl.kt          ← TTL cache
    infrastructure/SecretManagerConfig.kt        ← @Configuration, the SecretsManagerClient Bean
    infrastructure/SecretsEnvironmentPostProcessor.kt ← injects jwt.secret under prod

  outbox/                          ← the Outbox pattern (shared by multiple BCs for publishing events) — actual code
    OutboxEvent.kt                    ← @Entity (domain-events.md)
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                   ← writes an event as an Outbox row inside Repository.save()'s transaction
    OutboxPoller.kt                    ← polls via @Scheduled, publishes from the Outbox table → SQS (never called by a Command Service)
    OutboxConsumer.kt                  ← SmartLifecycle, SQS long polling → routes to EventHandlerRegistry
    EventHandlerRegistry.kt            ← eventType → handler mapping (Map-based, not a when branch)

  notification/                    ← the email-sending Technical Service (shared by both the Account+Card BCs) — actual code
    application/service/NotificationService.kt  ← interface (a Technical Service per domain-service.md)
    infrastructure/NotificationServiceImpl.kt    ← @Component, AWS SES SDK integration
    infrastructure/SesConfig.kt                  ← @Configuration, the SesClient Bean
    infrastructure/persistence/SentEmail.kt              ← @Entity, send history + sourceEventId-based Level 2 (Ledger) duplicate-send prevention
    infrastructure/persistence/SentEmailJpaRepository.kt

  taskqueue/                       ← the Task Queue pattern (scheduling.md, shared by multiple BCs) — actual code
    TaskOutbox.kt                     ← @Entity — a task_outbox row (the same purpose as outbox/OutboxEvent.kt)
    TaskOutboxJpaRepository.kt
    TaskQueue.kt                      ← interface — the port the Scheduler depends on (`enqueue`)
    TaskOutboxWriter.kt               ← the TaskQueue implementation, writes one row to task_outbox (handles the dedup UNIQUE constraint)
    TaskOutboxPoller.kt               ← polls via @Scheduled, publishes from task_outbox → SQS FIFO
    TaskQueueConsumer.kt              ← SmartLifecycle, SQS FIFO long polling → routes to TaskHandlerRegistry
    TaskHandlerRegistry.kt            ← taskType → handler mapping (1:1, contrasted with EventHandlerRegistry's 1:N)
    SchedulingConfig.kt               ← @Configuration, a dedicated TaskScheduler Bean (pool size 4)

  account/                         ← Bounded Context (1st domain)
    domain/ application/ infrastructure/ interfaces/
    infrastructure/scheduling/InterestPaymentScheduler.kt   ← @Scheduled, only enqueues the Task
    interfaces/task/PayInterestTaskController.kt             ← the Task Queue Interface adapter

  card/                            ← Bounded Context (2nd domain — communicates with account via Integration Events)
    domain/ application/ infrastructure/ interfaces/
    application/adapter/PaymentAdapter.kt                     ← synchronously queries the Payment BC (aggregating card usage)
    infrastructure/scheduling/CardStatementScheduler.kt       ← @Scheduled, only enqueues the Task
    interfaces/task/SendCardStatementTaskController.kt        ← the Task Queue Interface adapter

  payment/                         ← Bounded Context (3rd domain — Payment/Refund)
    domain/ application/ infrastructure/ interfaces/
    domain/RefundEligibilityService.kt   ← a Domain Service coordinating multiple Aggregates (Payment/Refund) — see domain-service.md
```

This placement carries the same idea as the NestJS implementation (`implementations/nestjs/docs/architecture/shared-modules.md`), which splits into `src/common/`, `src/database/`, `src/outbox/`, `src/auth/`, over into a Kotlin package structure. All seven shared packages (`common/`, `config/`, `auth/`, `secret/`, `outbox/`, `notification/`, `taskqueue/`) are actually built exactly as laid out here — authentication (`auth/`) and Secrets Manager integration (`secret/`) were added after `outbox/`, `notification/` was promoted from inside a BC to the top level once the Card BC needed to send email too, and `taskqueue/` was newly added with the introduction of the Task Queue.

### Not yet actually needed — items with only their placement criteria pre-decided for future expansion

- **`common/GlobalExceptionHandler.kt`** (`@RestControllerAdvice`): currently, error conversion is handled by `@ExceptionHandler` methods inside `AccountController` (see [error-handling.md](error-handling.md)) — promotion to `common/` should be considered once there are multiple Controllers.
- **`common/RateLimitingFilter.kt`**: this was just a directional item defined by [rate-limiting.md](rate-limiting.md) with no code yet at the time this was written; it has since actually been implemented (see [rate-limiting.md](rate-limiting.md)).
- **A DB-only `data class` under `config/`**: this repository doesn't have a separate class like `DatabaseProperties` — DB connection info is judged to be sufficiently handled by Spring Boot's standard `spring.datasource.*` relaxed binding (see [config.md](config.md)).

## Criteria for judging each package

| Package | What code belongs here | What doesn't belong here yet |
|---|---|---|
| `common/` | Framework-common code that contains no BC's business logic (error conversion, filters, ID generation) | Per-BC exceptions (`AccountException`) stay in each BC's `domain/` |
| `config/` | `data class`es dedicated to `@ConfigurationProperties` binding. These hold no logic themselves | A class with a `@Bean` factory method, like `SesConfig`, stays in the relevant BC/Technical Service's `infrastructure/` (e.g. `notification/infrastructure/SesConfig.kt`) — `config/` only collects the *type* of the configuration values |
| `auth/` | Infrastructure that multiple BCs need to reference in common, like authentication/authorization. Not a specific BC's business rule | Per-BC ownership checks among authorization rules (`account.ownerId == requesterId`) stay in each BC's Application Service |
| `outbox/` | Technical infrastructure (the Outbox table, Poller, Consumer) that propagates a Domain Event externally in a transaction-safe way | The event itself (`AccountCreatedEvent`, etc) stays in each BC's `domain/` — Outbox is just the piping that carries that event |
| `secret/` | Technical infrastructure that multiple BCs can reference in common, like Secrets Manager lookup/caching | Per-BC decisions about which secret to look up and when (e.g. whether an event handler uses a particular secret) stay in each BC's Application layer |
| `notification/` | Technical infrastructure (a Technical Service) that multiple BCs reference in common, like sending email — promoted once a second consumer (Card) appeared | Per-BC decisions about which event/Task sends a notification and when (`MoneyDepositedEventHandler`, `SendMonthlyCardStatementsService`, etc) stay in each BC's Application layer |
| `taskqueue/` | Technical infrastructure (the Task Outbox table, Poller, Consumer, routing registry) that propagates a Task to a queue in a transaction-safe way | What the Task itself does (per-taskType handler logic) stays in each BC's `interfaces/task/`+`application/command/` — taskqueue is just the piping that carries that Task (the same principle as outbox/) |

**The common criterion**: look at both "is this code reused across multiple BCs" and "does this code hold a specific BC's business invariant." If only the former applies, it's a shared package; if the latter applies (regardless of reuse), it stays inside that BC's 4 layers.

## Why `notification/` was promoted to a shared package

`notification/` originally lived inside `account/` as a **BC-owned Technical Service**, while only the Account BC used it. The promotion criterion this document defined from the start — "decide once a second BC appears and that BC also needs to send email" — actually arrived: the Card BC's monthly card-statement delivery (`SendMonthlyCardStatementsService`) needed to reuse the exact same SES sending technology. Between the two options (promote to a top-level shared package vs. each BC having its own independent notification logic), the sending technology itself (the SES SDK call, the send-history Ledger) had no reason to differ per BC, so the former was chosen — the same judgment `outbox/` already made, handling event publishing for all three BCs (Account/Card/Payment) in common.

## The equivalent of `@Global` — none, component scanning is sufficient

NestJS declares `DatabaseModule`/`OutboxModule` as `@Global()` so every module can access them without an explicit `imports`. Spring/Kotlin has no such concept at all — **as long as it's under the component-scan root (`com.example.accountservice`), any package's bean can be injected regardless of location**. As long as `outbox/OutboxWriter` is a `@Component`, `account/application/command/CreateAccountService` can constructor-inject it directly — not needing a separate concept like NestJS's "global module" is also an extension of the "package = an implicit boundary" idea covered in [module-pattern.md](module-pattern.md).

## Principles

- **All seven shared packages — `common/`/`config/`/`auth/`/`secret/`/`outbox/`/`notification/`/`taskqueue/` — have real usage sites** — authentication, Secrets Manager integration, Outbox, a second notification consumer (Card), and the Task Queue are each a real usage site. Items with no real usage site yet, like `GlobalExceptionHandler`, only have their placement criteria pre-decided.
- **The judgment criteria are "is it reused" and "does it own a business invariant"** — if neither, it's a shared package; if the latter, it stays inside the BC.
- **No separate declaration like `@Global` is needed** — as long as it's under the component-scan root, it can be injected regardless of package location.
- **A BC-owned Technical Service is never hastily promoted to a shared package** — it's only moved to the top level once there's an actual second consumer (`notification/` being a real example, with both Account and Card as actual usage sites).

### Related documents

- [directory-structure.md](directory-structure.md) — the current package tree, the "Placement of shared infrastructure" table
- [module-pattern.md](module-pattern.md) — how component scanning replaces package boundaries
- [domain-events.md](domain-events.md) — details of the Outbox pattern
- [scheduling.md](scheduling.md) — details of the Task Queue pattern
- [authentication.md](authentication.md) — details of the shared authentication module
- [secret-manager.md](secret-manager.md) — details of the Secrets Manager integration
- [config.md](config.md) — the `@ConfigurationProperties` data class design
