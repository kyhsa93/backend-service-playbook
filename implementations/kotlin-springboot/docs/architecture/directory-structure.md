# Directory Structure — Kotlin Spring Boot

> For the framework-agnostic principles, see [root directory-structure.md](../../../../docs/architecture/directory-structure.md).

## The actual package tree — the Account domain

The actual structure of `examples/src/main/kotlin/com/example/accountservice/`.

```
com.example.accountservice/
  AccountServiceApplication.kt       ← the @SpringBootApplication entry point

  common/                           ← shared infrastructure (belongs to no specific BC)
    CorrelationIdFilter.kt            ← Filter, sets the Correlation ID in MDC (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt      ← HandlerInterceptor, request/response logging (cross-cutting-concerns.md)
    WebConfig.kt                      ← @Configuration, registers the Interceptor
    GenerateId.kt                     ← Aggregate ID generation util (a top-level function) (aggregate-id.md)

  config/                           ← the collection of @ConfigurationProperties data classes (config.md)
    AwsProperties.kt                  ← prefix="aws", @Validated
    SesProperties.kt                  ← prefix="ses", @Validated

  auth/                             ← the shared authentication module (authentication.md)
    application/
      AuthService.kt                   ← JWT issuance
    infrastructure/
      JwtAuthenticationFilter.kt       ← the Bearer-token-verifying Filter
      SecurityConfig.kt                ← @Configuration, whitelisted paths
    interfaces/
      rest/
        AuthController.kt              ← the sign-in endpoint

  secret/                           ← Secrets Manager integration (secret-manager.md)
    application/
      service/
        SecretService.kt                ← interface, no Spring dependency
    infrastructure/
      SecretServiceImpl.kt             ← @Component, TTL cache
      SecretManagerConfig.kt           ← @Configuration, the SecretsManagerClient Bean
      SecretsEnvironmentPostProcessor.kt ← injects jwt.secret into the Environment under the prod profile, registered via META-INF/spring.factories

  outbox/                           ← the Domain Event Outbox (domain-events.md)
    OutboxEvent.kt                    ← @Entity
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                   ← writes an Outbox row inside Repository.save()'s transaction
    OutboxPoller.kt                    ← polls via @Scheduled, publishes from the Outbox table → SQS (never called by a Command Service)
    OutboxConsumer.kt                  ← SmartLifecycle, SQS long polling → routes to EventHandlerRegistry
    EventHandlerRegistry.kt            ← eventType → handler mapping (Map-based, not a when branch)

  account/
    domain/                          ← no framework dependency (no Spring/JPA imports, checked by the harness's domain-purity check)
      Account.kt                     ← Aggregate Root — plain Kotlin, JPA mapping is entirely handled by AccountJpaEntity
      Transaction.kt                 ← child Entity — plain Kotlin, JPA mapping is entirely handled by TransactionJpaEntity
      Money.kt                       ← Value Object — a plain data class, JPA mapping is entirely handled by MoneyEmbeddable
      AccountStatus.kt               ← enum class
      TransactionType.kt             ← enum class
      AccountException.kt            ← the sealed class error hierarchy
      AccountRepository.kt           ← the Repository interface (a plain interface, no Spring dependency)
      AccountCreatedEvent.kt         ← Domain Event (data class)
      AccountSuspendedEvent.kt
      AccountReactivatedEvent.kt
      AccountClosedEvent.kt
      MoneyDepositedEvent.kt
      MoneyWithdrawnEvent.kt

    application/
      command/
        CreateAccountService.kt      ← @Service — one class per use case
        CreateAccountCommand.kt      ← data class
        CreateAccountResult.kt       ← data class
        DepositService.kt / DepositCommand.kt
        WithdrawService.kt / WithdrawCommand.kt
        SuspendAccountService.kt / SuspendAccountCommand.kt
        ReactivateAccountService.kt / ReactivateAccountCommand.kt
        CloseAccountService.kt / CloseAccountCommand.kt
        TransactionResult.kt         ← data class (shared response for Deposit/Withdraw)
      query/
        AccountQuery.kt              ← the read-only Query interface (naming/placement from root cqrs-pattern.md)
        GetAccountService.kt / GetAccountResult.kt
        GetTransactionsService.kt / GetTransactionsResult.kt
      event/                            ← Handlers that process events drained from the Outbox (checked by the harness's event-placement rule)
        AccountCreatedEventHandler.kt
        MoneyDepositedEventHandler.kt
        MoneyWithdrawnEventHandler.kt
        AccountSuspendedEventHandler.kt
        AccountReactivatedEventHandler.kt
        AccountClosedEventHandler.kt

    infrastructure/
      persistence/
        AccountJpaEntity.kt              ← the JPA-mapping-only counterpart of domain.Account (@Entity)
        TransactionJpaEntity.kt          ← the JPA-mapping-only counterpart of domain.Transaction (@Entity)
        MoneyEmbeddable.kt               ← the JPA-mapping-only counterpart of domain.Money (@Embeddable + data class)
        AccountMapper.kt                 ← handles Account ↔ AccountJpaEntity conversion exclusively (an internal object)
        TransactionMapper.kt             ← handles Transaction ↔ TransactionJpaEntity conversion exclusively (an internal object)
        AccountJpaRepository.kt          ← extends JpaRepository&lt;AccountJpaEntity, Long&gt;
        TransactionJpaRepository.kt      ← extends JpaRepository&lt;TransactionJpaEntity, Long&gt;
        AccountRepositoryImpl.kt         ← @Repository, the AccountRepository implementation (converts Entity↔Domain via the Mapper)
      scheduling/
        InterestPaymentScheduler.kt      ← @Component, @Scheduled — only enqueues a Task (scheduling.md)

    interfaces/
      rest/
        AccountController.kt             ← @RestController
        Schemas.kt                       ← the collection of Request/Response data classes
      task/
        PayInterestTaskController.kt     ← the Task Queue Interface adapter (scheduling.md)
```

Sending email (`NotificationService`) lives not inside `account/` but in the top-level `notification/` package — see the "notification" section below.

---

## The Account module — mapping to the root's 4 layers

| Root layer | This repository's package | Notes |
|---|---|---|
| domain/ | `account/domain/` | plain Kotlin — no Spring/JPA imports. JPA mapping is split off into infrastructure (see below) |
| application/ | `account/application/{command,query,event,integrationevent}/` | adding the Card BC introduced `integrationevent/` (the equivalent of the root's `integration-event/` — Kotlin package names can't contain hyphens, so it's written as one word). Account never synchronously calls another BC, so `adapter/` is still unused here — it lives in the Card BC (`card/application/adapter/`) instead |
| interface/ | `account/interfaces/rest/` | the root uses `interface/` (singular); this repository uses `interfaces/` (plural), the Java/Kotlin convention |
| infrastructure/ | `account/infrastructure/persistence/` | JpaEntity/Embeddable + Mapper + the Repository implementation are placed under a `persistence/` subpackage |

### domain/JPA separation — JpaEntity + Mapper

The root principle is "domain/ never imports any framework." This repository applies that principle without exception: `Account`/`Transaction`/`Money` in `domain/` are plain Kotlin classes (`data class`, null-safety) with no `jakarta.persistence` annotations attached at all, and JPA mapping is handled entirely by their dedicated counterparts in `infrastructure/persistence/`.

| domain (plain) | infrastructure/persistence (JPA mapping) | Role |
|---|---|---|
| `Account` (Aggregate Root) | `AccountJpaEntity` (`@Entity`) | `@Id`/`@Column`/`@Embedded`/`@Enumerated` column mapping |
| `Transaction` (child Entity) | `TransactionJpaEntity` (`@Entity`) | same as above (insert-only since it's immutable after creation) |
| `Money` (Value Object) | `MoneyEmbeddable` (`@Embeddable`) | `amount`/`currency` embeddable column mapping |

- **The Mapper handles conversion exclusively.** `AccountMapper`/`TransactionMapper` (`internal object`) handle bidirectional Entity ↔ Domain conversion, and are used only inside `AccountRepositoryImpl`. The Domain/Application layers don't even know the JpaEntity/Mapper exist.
- **Restoration goes through `reconstitute()`.** The Mapper's `toDomain()` calls the domain factory `Account.reconstitute(...)`/`Transaction.reconstitute(...)` — unlike `create()`, this doesn't produce domain events; it just reconstructs an already-committed state as-is.
- **Saving preserves the PK.** `AccountMapper.updateEntity(existing, account)` overwrites only the mutable fields of an existing row (with its DB-generated `id`) to handle it as an update, while a new one is inserted via `toNewEntity(account)` (no PK). `AccountRepositoryImpl.save()` determines whether an existing row exists via `findByAccountId` and branches between the two.
- **JPQL targets the JpaEntity.** `AccountRepositoryImpl`'s dynamic queries are written as `SELECT a FROM AccountJpaEntity a ...`, with the result mapped and returned via `AccountMapper::toDomain`.

The harness's `domain-purity` check FAILs not just on Spring stereotypes (`@Service`/`@Component`/`@Repository`/`@Controller`) but also on `jakarta.persistence` imports in `domain/` — it automatically catches the violation of attaching a JPA annotation to a domain class. (This separation corresponds exactly to the same structure in java-springboot — `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper`.)

---

## notification — a top-level shared Technical Service

Sending email is not a separate Bounded Context — it's the actual implementation of a **Technical Service** as defined by [domain-service.md](../../../../docs/architecture/domain-service.md) (abstracting technical infrastructure such as encryption, file storage, sending email, etc). Since both the Account BC and the Card BC (sending the monthly card statement) need the same `NotificationService`, per [shared-modules.md](shared-modules.md)'s promotion criteria ("once a second BC actually needs it"), it's placed in a top-level `notification/` package that's a sibling of `account/`·`card/` — the same placement as `secret/`/`outbox/`.

**Why there's no domain/**: a Technical Service is pure technical functionality (calling SES), not an Aggregate carrying business invariants, so it needs no Aggregate Root or Repository. Instead, its own Entity, `SentEmail` (the send history), and a JPA Repository live in `notification/infrastructure/persistence/` — this history is a technical record kept for audit/debugging purposes rather than a domain rule, so it wasn't promoted to the domain layer.

```
notification/
  application/service/
    NotificationService.kt           ← interface (no Spring dependency) — abstracts sending email
  infrastructure/
    NotificationServiceImpl.kt       ← @Component, the SES-integrated implementation
    SesConfig.kt                     ← @Configuration, the SesClient Bean
    persistence/
      SentEmail.kt                   ← @Entity (send history)
      SentEmailJpaRepository.kt
```

```kotlin
// notification/application/service/NotificationService.kt — the interface, no Spring dependency
interface NotificationService {
    fun sendEmail(accountId: String, eventType: String, sourceEventId: String, recipient: String, subject: String, body: String)
}
```

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — the implementation, uses the AWS SES SDK
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,   // config/SesProperties.kt, see config.md — not @Value
) : NotificationService { /* ... */ }
```

Each Handler in `account/application/event/` (`AccountCreatedEventHandler`, etc.) and `card/application/command/SendMonthlyCardStatementsService` depend only on this interface, and have no idea how the actual SES call is made (SDK version, credential method) — exactly as the Technical Service pattern intends, the Application layer is isolated from infrastructure details.

---

## File naming rules — Kotlin/Java convention (not the root's kebab-case)

The root document specifies kebab-case filenames (`order-repository.ts`), but in the Kotlin/Java ecosystem the standard convention is **filename = the top-level public class name (PascalCase)** (both `kotlinc` and IntelliJ assume this).

| Kind | Location | Filename pattern | Example |
|------|------|------------|------|
| Aggregate Root | `domain/` | `<AggregateRoot>.kt` | `Account.kt` |
| Value Object | `domain/` | `<ValueObject>.kt` | `Money.kt` |
| Domain Event | `domain/` | `<PascalCase past tense>Event.kt` | `AccountCreatedEvent.kt` |
| Repository interface | `domain/` | `<Aggregate>Repository.kt` | `AccountRepository.kt` |
| Repository implementation | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl.kt` | `AccountRepositoryImpl.kt` |
| Command Service | `application/command/` | `<Verb><Noun>Service.kt` | `CreateAccountService.kt` |
| Command | `application/command/` | `<Verb><Noun>Command.kt` | `CreateAccountCommand.kt` |
| Query Service | `application/query/` | `<Verb><Noun>Service.kt` | `GetAccountService.kt` |
| Result | `application/query/` or `command/` | `<Verb><Noun>Result.kt` | `GetAccountResult.kt` |
| Event Handler | `application/event/` | `<DomainEvent>Handler.kt` | `AccountCreatedEventHandler.kt` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller.kt` | `AccountController.kt` |
| Error hierarchy | `domain/` | `<Domain>Exception.kt` (a sealed class + its subclasses in one file) | `AccountException.kt` |

The harness's `file-naming` check (`^[A-Z][A-Za-z0-9]*$`) actually enforces this rule.

---

## Placement of shared infrastructure

Most of the shared packages the root specifies sit at the project root (`com.example.accountservice.*`, outside any BC package) — the Account and Card BCs share these together.

| Package | Purpose | Current state |
|---|---|---|
| `common/` | Correlation ID Filter, request-logging Interceptor, ID generation util (`GenerateId.kt`), etc | **present** — see [cross-cutting-concerns.md](cross-cutting-concerns.md), [aggregate-id.md](aggregate-id.md) |
| `config/` | the collection of `@ConfigurationProperties` data classes (`AwsProperties`, `SesProperties`) | **present** — see [config.md](config.md) |
| `auth/` | JWT issuance/verification, Spring Security configuration | **present** — see [authentication.md](authentication.md) |
| `secret/` | AWS Secrets Manager integration, TTL cache | **present** — see [secret-manager.md](secret-manager.md) |
| `outbox/` | the Domain Event Outbox, Writer, Poller, Consumer | **present** — see [domain-events.md](domain-events.md) |
| `notification/` | the email-sending Technical Service (SES), send history | **present** — shared by both the Account and Card BCs. See the "notification" section above |
| `taskqueue/` | the Task Outbox, TaskQueue port, Poller, Consumer, TaskHandlerRegistry | **present** — see [scheduling.md](scheduling.md) |

When a second BC is added or the scale grows further, each package's internal structure can be broken down further — for now, the placement [shared-modules.md](shared-modules.md) defines is followed as-is.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction, responsibilities
- [repository-pattern.md](repository-pattern.md) — Repository placement and naming
- [tactical-ddd.md](tactical-ddd.md) — internal design of domain/
