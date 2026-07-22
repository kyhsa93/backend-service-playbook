# Shared Code Structure (Spring Boot)

> A document contrasted with NestJS. This corresponds to NestJS's `src/common/`/`src/database/`/`src/outbox/`/`src/auth/` (each a separate `@Module`), but Spring Boot has no separate container unit called a "shared module" — as [module-pattern.md](module-pattern.md) explains, the package itself is already the conventional boundary.

## Current actual state — `common/`/`config/`/`outbox/`/`auth/` all exist

Looking at the full tree of `examples/src/main/java/com/example/accountservice/`, the top-level packages are `account/` (1st domain), `card/` (2nd domain), `payment/` (3rd domain — Payment/Refund, `RefundEligibilityService`), `auth/` (authentication/sign-up), `common/`, `config/`, `outbox/`, `taskqueue/`. `notification` (a Technical Service) sits inside each of `account`/`card` separately — rather than being top-level, each domain has its own separate interface+implementation inside it (`account/application/service/`+`account/infrastructure/notification/`, `card/application/service/`+`card/infrastructure/notification/`) — a real example of the principle that code isn't preemptively promoted to a shared package before it's actually shared (both domains use the same concept of "sending email," but since the implementations each have their own separate send-history table, no shared instance is used).

The packages actually shared by multiple domains and living at the top level are `common/` (`IdGenerator`, the Filter/Interceptor in `web/`, `SecretService`), `config/` (`@ConfigurationProperties` records), `outbox/` (`OutboxEvent`/`OutboxWriter`/`OutboxPoller`/`OutboxConsumer`/`OutboxEventHandler`), and `taskqueue/` (`TaskOutboxEntry`/`TaskOutboxWriter`/`TaskOutboxPoller`/`TaskConsumer`/`TaskHandler` — see scheduling.md; a sibling structure to `outbox/`, but with its own dedicated table/queue for Tasks). There is no `database/` — while the scenario of bundling multiple Repository saves into one transaction does exist (a transfer between accounts, `AccountRepository.saveAccounts()`), Spring's `@Transactional` (based on an AOP proxy) already plays that role, so no separate shared package is needed — a Repository method that needs a transaction boundary just needs `@Transactional` attached (see [persistence.md](persistence.md)).

For details, see the actual tree in [directory-structure.md](directory-structure.md) — this document maps that placement against NestJS's shared-module structure.

---

## The actual placement

```
com.example.accountservice/
  common/                  # framework-independent pure utilities + shared Interface-layer components
    IdGenerator.java        # see aggregate-id.md — pure Java, imports no framework
    web/
      GlobalExceptionHandler.java   # @RestControllerAdvice, see error-handling.md
      CorrelationIdFilter.java      # see cross-cutting-concerns.md
      RequestLoggingInterceptor.java
      RateLimitFilter.java
    service/
      SecretService.java            # interface — see secret-manager.md
    infrastructure/
      SecretServiceImpl.java        # implementation with a TTL cache
    config/
      SecretsEnvironmentPostProcessor.java   # see secret-manager.md, injects jwt.secret in the prod profile

  config/                  # dedicated to @ConfigurationProperties records (a distinct role from common/config)
    AwsProperties.java       # see config.md
    SesProperties.java
    JwtProperties.java
    SecurityConfig.java      # @Configuration, the JWT SecurityFilterChain — see authentication.md
    WebConfig.java           # Filter/Interceptor registration — see cross-cutting-concerns.md

  outbox/                   # see domain-events.md
    OutboxEvent.java          # @Entity — maps the Outbox table
    OutboxEventJpaRepository.java
    OutboxEventHandler.java   # the interface each event type's Handler implements
    OutboxWriter.java         # writes events as Outbox rows inside the Repository.save() transaction
    OutboxPoller.java         # @Scheduled(fixedDelay=1000) — polls the Outbox table and publishes to SQS
    OutboxConsumer.java       # SmartLifecycle — receives from SQS and routes to an OutboxEventHandler

  taskqueue/                # see scheduling.md — a sibling of outbox/, dedicated to the Task Queue
    TaskOutboxEntry.java      # @Entity — maps the task_outbox table (includes groupId/deduplicationId)
    TaskOutboxJpaRepository.java
    TaskHandler.java          # the interface each taskType's handler implements — implementations live in each domain's interfaces/task/
    TaskOutboxWriter.java     # a Scheduler (or Command Service) writes a task_outbox row
    TaskOutboxPoller.java     # @Scheduled(fixedDelay=1000) — polls task_outbox and publishes to the Task Queue (SQS FIFO)
    TaskConsumer.java         # SmartLifecycle — receives from the Task Queue and routes to a TaskHandler

  auth/                     # authentication/sign-up — see authentication.md
    domain/                   # the Credential Aggregate (userId + bcrypt hash)
    application/              # SignInService/SignUpService
    infrastructure/           # BCryptPasswordHasher, CredentialRepositoryImpl
    interfaces/rest/           # AuthController

  account/                  # 1st domain
    domain/ application/ infrastructure/ interfaces/
    application/service/NotificationService.java        # domain-scoped Technical Service
    infrastructure/notification/                          # implementation — used only by account, not a shared package
    infrastructure/scheduling/InterestPaymentScheduler.java  # scheduling.md Feature 1
    interfaces/task/PayInterestTaskController.java            # scheduling.md Feature 1

  card/                     # 2nd domain — communicates with account via Integration Events
    domain/ application/ infrastructure/ interfaces/
    application/service/NotificationService.java        # domain-scoped Technical Service — a separate implementation from account's
    infrastructure/notification/                          # implementation — used only by card, not a shared package
    infrastructure/scheduling/CardStatementScheduler.java    # scheduling.md Feature 2
    interfaces/task/SendCardStatementTaskController.java     # scheduling.md Feature 2

  payment/                  # 3rd domain — Payment/Refund, an example of a Domain Service coordinating multiple Aggregates
    domain/ application/ infrastructure/ interfaces/
    domain/RefundEligibilityService.java   # a Domain Service coordinating multiple Aggregates (Payment/Refund) — see domain-service.md

  AccountServiceApplication.java
```

Only items used broadly across multiple domains/Technical Services live at the top level (`common/`, `config/`, `outbox/`), while a Technical Service used by just one domain, like `notification`, stays inside that domain — `database/` (a utility for bundling multiple Repositories into one transaction) was never created even after that scenario (a transfer between accounts) actually arose, because Spring's `@Transactional` already plays that role, so no separate shared package is needed.

**Why `common/` and `config/` are split**: `common/` holds domain-agnostic **general-purpose utilities/Interface-layer components** (ID generation, global exception handling, filters, secret lookup), while `config/` is dedicated to **records for `@ConfigurationProperties` binding + Security/Web configuration** (see [config.md](config.md)). The latter has the constraint that it's only ever injected in the Infrastructure layer, so their roles are clearly distinct and they're kept separate.

---

## Mapping to NestJS's shared modules

| NestJS (`@Module`, `@Global`) | Spring Boot equivalent | State in this repository |
|---|---|---|
| `src/common/` (filters, interceptors, utilities) | The `common/` package — a mix of `@Component`/pure utilities | Present — `IdGenerator`, the Filter/Interceptor in `web/`, `SecretService` (see secret-manager.md) |
| `src/database/` (`@Global` — DataSource, TransactionManager) | A `database/` package — except Spring has no need for the `@Global` concept at all | Absent (JPA's `DataSource` is already globally provided by auto-configuration. The scenario of bundling multiple Repositories together — a transfer between accounts, `AccountRepository.saveAccounts()` — is also handled by `@Transactional` instead, so no separate shared utility is needed — see persistence.md) |
| `src/outbox/` (`@Global` — OutboxWriter/Poller/Consumer) | The `outbox/` package | Present — `OutboxWriter`/`OutboxPoller`/`OutboxConsumer`/`OutboxEventHandler` (see domain-events.md) |
| (No corresponding package in NestJS — the Task Queue is a new concept separate from domain-events.md's Outbox) | The `taskqueue/` package | Present — `TaskOutboxWriter`/`TaskOutboxPoller`/`TaskConsumer`/`TaskHandler` (see scheduling.md) |
| `src/auth/` (a shared authentication module) | The `auth/` package | Present — the `Credential` Aggregate + `SignInService`/`SignUpService` (see authentication.md) |

**Why Spring has no `@Global` decorator**: NestJS's module scope is closed by default, requiring `@Global` to be declared explicitly for a provider to be injectable from any module without `imports`. In Spring Boot, global scope is the default from the start (see the "fundamental difference" section of [module-pattern.md](module-pattern.md)) — putting a `DataSource` `@Bean` in `database/` already makes it injectable from anywhere, with no special marker needed. In other words, in Spring, a "shared module" isn't a concept requiring a special declaration the way it does in NestJS — it's **simply an organizational convention of splitting packages**.

---

## The criteria for separating shared code from domain code

- **If two or more domains/Technical Services reference it, it's shared code.** `IdGenerator` is a utility used by `account`/`card`/`auth` all alike, so it belongs in `common/`. Conversely, `NotificationServiceImpl` is used only by the `account` domain, so it stays in `account/infrastructure/notification/` rather than a top-level shared package.
- **Shared code still follows layer discipline exactly.** `common/IdGenerator` is a pure utility importing no framework, so it can be called directly from the Domain layer (`Account.create()`). By contrast, `common/web/GlobalExceptionHandler` depends on Spring MVC types (`ResponseEntity`, `@RestControllerAdvice`), so it's Interface-layer-flavored shared code and must never be referenced from Domain/Application.
- **Something is promoted to the top level only once multiple domains genuinely share it.** The absence of `database/` is a good contrasting example — even though the scenario of bundling multiple Repositories into one transaction exists (a transfer between accounts), Spring's `@Transactional` already plays that role, so there's no need to create a separate shared package at all.

---

### Related documents

- [directory-structure.md](directory-structure.md) — the full actual tree
- [aggregate-id.md](aggregate-id.md) — the actual placement of `common.IdGenerator`
- [secret-manager.md](secret-manager.md) — the actual placement of `common.config.SecretsEnvironmentPostProcessor`
- [domain-events.md](domain-events.md) — the actual structure of the `outbox/` package
- [authentication.md](authentication.md) — the actual structure of the `auth/` package
- [module-pattern.md](module-pattern.md) — why Spring doesn't require the concept of a "shared module" at all
