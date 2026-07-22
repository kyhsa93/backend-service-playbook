# Directory Structure (Spring Boot)

> For the framework-agnostic principles, see the root [directory-structure.md](../../../../docs/architecture/directory-structure.md).

## Current actual structure

This is the actual tree of `examples/src/main/java/com/example/accountservice/` — it has both `account` (1st domain) and `card` (2nd domain, communicating with Account BC via Integration Events):

```
com.example.accountservice/
  AccountServiceApplication.java         # @SpringBootApplication entry point

  account/
    domain/                              # framework-independent pure domain (see layer-architecture.md)
      Account.java                       # Aggregate Root — pure object; JPA mapping is handled entirely by AccountJpaEntity
      Transaction.java                   # child Entity — pure object; JPA mapping is handled entirely by TransactionJpaEntity
      Money.java                         # Value Object — pure record; JPA mapping is handled entirely by MoneyEmbeddable
      AccountStatus.java                 # status enum
      TransactionType.java               # transaction-type enum
      AccountFindQuery.java              # dynamic query-condition record
      AccountException.java              # domain exception + ErrorCode enum
      AccountRepository.java             # Repository interface
      AccountsWithCount.java             # record for a merged list+count return
      TransactionsWithCount.java
      AccountCreatedEvent.java           # Domain Event (record)
      MoneyDepositedEvent.java
      MoneyWithdrawnEvent.java
      AccountSuspendedEvent.java
      AccountReactivatedEvent.java
      AccountClosedEvent.java

    application/
      command/                           # write use cases
        CreateAccountService.java        # @Service @Transactional
        DepositService.java
        WithdrawService.java
        SuspendAccountService.java
        ReactivateAccountService.java
        CloseAccountService.java
        DeleteAccountService.java        # soft delete — deletes only accounts in CLOSED status
        CreateAccountCommand.java        # record
        DepositCommand.java
        WithdrawCommand.java
        SuspendAccountCommand.java
        ReactivateAccountCommand.java
        CloseAccountCommand.java
        DeleteAccountCommand.java
        CreateAccountResult.java
        TransactionResult.java
        PayInterestCommand.java          # record — periodic interest-payment batch (scheduling.md Feature 1)
        PayInterestService.java          # @Service — the system use case invoked by the Task Controller
      query/                             # read use cases
        AccountQuery.java                # Query interface — separate from the write-side AccountRepository (see cqrs-pattern.md)
        GetAccountService.java           # @Service @Transactional(readOnly = true) — uses AccountQuery
        GetTransactionsService.java      # uses AccountQuery (findTransactions — returns a merged list+count)
        GetAccountResult.java
        GetTransactionsResult.java
      event/                             # Domain Event Handlers processing events drained from the Outbox
        AccountCreatedEventHandler.java  # @Component — implements outbox.OutboxEventHandler
        MoneyDepositedEventHandler.java
        MoneyWithdrawnEventHandler.java
        AccountSuspendedEventHandler.java   # sends notification + writes the account.suspended.v1 Integration Event
        AccountReactivatedEventHandler.java
        AccountClosedEventHandler.java      # sends notification + writes the account.closed.v1 Integration Event
      integrationevent/                  # public contracts published to Card BC (see cross-domain-communication.md)
        AccountSuspendedIntegrationEventV1.java
        AccountClosedIntegrationEventV1.java
      service/                           # domain-scoped Technical Service — notification (see below)
        NotificationService.java         # interface — Technical Service

    infrastructure/
      persistence/
        AccountJpaEntity.java            # the JPA-mapping-only counterpart of domain.Account — @Entity
        TransactionJpaEntity.java        # the JPA-mapping-only counterpart of domain.Transaction — @Entity
        MoneyEmbeddable.java             # the JPA-mapping-only counterpart of domain.Money — @Embeddable
        AccountMapper.java               # dedicated Account ↔ AccountJpaEntity conversion (package-private)
        TransactionMapper.java           # dedicated Transaction ↔ TransactionJpaEntity conversion (package-private)
        AccountJpaRepository.java        # extends JpaRepository<AccountJpaEntity, Long>
        TransactionJpaRepository.java    # extends JpaRepository<TransactionJpaEntity, Long>
        AccountRepositoryImpl.java       # @Repository @Transactional — implements AccountRepository + AccountQuery (includes Outbox write)
      notification/                      # domain-scoped Technical Service implementation — used only by account (see below)
        NotificationServiceImpl.java     # @Component — SES implementation
        SesConfig.java                   # @Configuration — SesClient bean
        persistence/
          SentEmail.java                 # @Entity — send history
          SentEmailRepository.java       # extends Spring Data JpaRepository directly (see below)
      scheduling/                        # see scheduling.md
        InterestPaymentScheduler.java    # @Component @Scheduled(cron) — runs daily at 3 AM, only writes to task_outbox

    interfaces/
      rest/
        AccountController.java           # @RestController
        CreateAccountRequest.java        # record — Interface DTO
        DepositRequest.java
        WithdrawRequest.java
        ErrorResponse.java                # record — the 4 fields statusCode/code/message/error (see error-handling.md)
      task/                              # Task Queue input adapter (see scheduling.md)
        PayInterestTaskController.java   # implements taskqueue.TaskHandler — only delegates to PayInterestService

  card/                                  # 2nd domain — communicates with Account BC via Integration Events
    domain/
      Card.java                          # Aggregate Root
      CardStatus.java
      CardException.java
      CardRepository.java
    application/
      command/
        IssueCardService.java
        CancelCardsByAccountService.java    # bulk-cancels cards on receiving the Account-closed Integration Event
        SuspendCardsByAccountService.java   # bulk-suspends cards on receiving the Account-suspended Integration Event
        IssueCardCommand.java
        CancelCardsByAccountCommand.java
        SuspendCardsByAccountCommand.java
        IssueCardResult.java
        SendCardStatementCommand.java       # record — monthly card-statement batch (scheduling.md Feature 2)
        SendCardStatementService.java       # @Service — the system use case invoked by the Task Controller
      query/
        CardQuery.java
        GetCardService.java
        GetCardResult.java
      event/                             # Handlers processing received Account Integration Events
        AccountSuspendedIntegrationEventHandler.java
        AccountClosedIntegrationEventHandler.java
        AccountIntegrationEventPayload.java
      adapter/                           # ACL interfaces for querying other BCs (see cross-domain.md)
        AccountAdapter.java
        PaymentAdapter.java              # queries Payment BC's usage statistics — used by SendCardStatementService
      service/                           # domain-scoped Technical Service — used only by card
        NotificationService.java         # interface — same rationale as account/application/service/NotificationService
    infrastructure/
      AccountAdapterImpl.java            # AccountAdapter implementation — synchronously queries Account BC
      CardPaymentAdapterImpl.java        # PaymentAdapter implementation — synchronously queries Payment BC's PaymentQuery
      persistence/
        CardJpaEntity.java
        CardMapper.java
        CardJpaRepository.java
        CardRepositoryImpl.java
      notification/                      # domain-scoped Technical Service implementation — used only by card
        CardNotificationServiceImpl.java # @Component — SES implementation (same structure as account/infrastructure/notification)
        persistence/
          CardSentEmail.java             # @Entity — send history (same structure as account's SentEmail, Card BC-specific)
          CardSentEmailRepository.java
      scheduling/                        # see scheduling.md
        CardStatementScheduler.java      # @Component @Scheduled(cron) — runs at 4 AM on the 1st of every month, only writes to task_outbox
    interfaces/
      rest/
        CardController.java
        IssueCardRequest.java
      task/                              # Task Queue input adapter (see scheduling.md)
        SendCardStatementTaskController.java  # implements taskqueue.TaskHandler — only delegates to SendCardStatementService

  auth/                                  # authentication/sign-up — a separate package from the Account domain
    domain/
      Credential.java                    # Aggregate Root — userId + bcrypt hash
      CredentialsWithCount.java
      CredentialFindQuery.java
      CredentialRepository.java
      AuthException.java
    application/
      command/
        SignInService.java                # compares against the stored hash, then issues a token
        SignUpService.java                # check for duplicates → hash → save
        SignInCommand.java
        SignUpCommand.java
        SignInResult.java
      query/
        CredentialQuery.java
      service/
        PasswordHasher.java               # interface — Technical Service
    infrastructure/
      BCryptPasswordHasher.java           # PasswordHasher implementation
      persistence/
        CredentialJpaEntity.java
        CredentialMapper.java
        CredentialJpaRepository.java
        CredentialRepositoryImpl.java
    interfaces/
      rest/
        AuthController.java
        SignInRequest.java
        SignUpRequest.java

  common/                                # domain-agnostic shared infrastructure (see shared-modules.md)
    IdGenerator.java                     # 32-character hex ID-generation utility (see aggregate-id.md)
    web/
      CorrelationIdFilter.java           # Correlation ID — MDC propagation
      RequestLoggingInterceptor.java
      RateLimitFilter.java
      GlobalExceptionHandler.java        # @RestControllerAdvice — global exception handling
    service/
      SecretService.java                 # interface — Secrets Manager access
    infrastructure/
      SecretServiceImpl.java             # implementation with a TTL cache
    config/
      SecretsEnvironmentPostProcessor.java   # injects jwt.secret early at startup in the prod profile

  config/                                # per-concern @ConfigurationProperties (see config.md)
    AwsProperties.java
    SesProperties.java
    JwtProperties.java
    SecurityConfig.java                  # the JWT SecurityFilterChain
    WebConfig.java                       # Filter/Interceptor registration
    OpenApiConfig.java                   # springdoc title/version + bearer-jwt security scheme (see bootstrap.md)

  outbox/                                # domain-agnostic shared infrastructure (see shared-modules.md) — not Account-only
    OutboxEvent.java                     # @Entity — the Outbox table
    OutboxEventJpaRepository.java
    OutboxEventHandler.java              # the interface each event type's Handler implements
    OutboxWriter.java                    # writes events as Outbox rows inside the Repository.save() transaction
    OutboxPoller.java                    # @Scheduled(fixedDelay=1000) — polls the Outbox table and publishes to SQS (see domain-events.md)
    OutboxConsumer.java                  # SmartLifecycle — receives from SQS and routes to an OutboxEventHandler (see domain-events.md)

  taskqueue/                             # domain-agnostic shared infrastructure (see shared-modules.md) — a sibling of outbox/, dedicated to Task
    TaskOutboxEntry.java                 # @Entity — the task_outbox table (includes groupId/deduplicationId, for FIFO queue support)
    TaskOutboxJpaRepository.java
    TaskHandler.java                     # the interface each taskType()'s handler implements — implementations live in each domain's interfaces/task/
    TaskOutboxWriter.java                # a Scheduler (or Command Service) writes a task_outbox row
    TaskOutboxPoller.java                # @Scheduled(fixedDelay=1000) — polls task_outbox and publishes to the Task Queue (SQS FIFO) (see scheduling.md)
    TaskConsumer.java                    # SmartLifecycle — receives from the Task Queue and routes to a TaskHandler (see scheduling.md)
```

Tests mirror the same package structure under `src/test/java/com/example/accountservice/` (the standard Gradle source-set layout).

---

## `interfaces` (plural) — avoiding a Java reserved word

Since `interface` is a Java language keyword, it cannot be used as a package name. Where the root document uses `interface/` (singular), this repository uses `interfaces/` (plural) — the same kind of per-language accommodation that go/kotlin-springboot each make to fit their own language's constraints.

---

## `notification` — a Technical Service inside the `account` domain

Sending notifications (email) is placed **inside the `account` domain** — at `account/application/service/` and `account/infrastructure/notification/` — rather than as a top-level package sibling to `account/`, because only `account` actually uses this feature. This directly demonstrates the Technical Service pattern example from [domain-service.md](../../../../docs/architecture/domain-service.md):

1. **Technical Service interface/implementation separation** — `account/application/service/NotificationService` (interface) is separated from `account/infrastructure/notification/NotificationServiceImpl` (implementation).
2. **A self-contained store for notifications** — `SentEmail`/`SentEmailRepository` (`account/infrastructure/notification/persistence/`) independently manages send history, and is not shared with `Account`/`Transaction`'s Repository.

`account/infrastructure/notification/` has no `domain/` package — because it's not an Aggregate, but a pure technical service (sending email). `SentEmail` is merely a history-recording Entity, not an Aggregate Root encapsulating business invariants, so it's placed directly in `infrastructure/notification/persistence/`.

`SentEmailRepository` isn't split into a separate interface + implementation like `AccountRepository` — it's just a single interface directly extending Spring Data's `JpaRepository`. This is a pragmatic judgment: send history is a simple record with no business rules, so it doesn't need the benefits the Repository pattern's interface/implementation split provides (swappability, protecting domain purity). See "A dedicated Repository interface only for the Aggregate Root" in [repository-pattern.md](repository-pattern.md).

---

## File/class naming conventions

| Target | Rule | Example |
|------|------|------|
| File name/class name | `PascalCase`, file name = public class name | `AccountRepository.java` |
| Package name | lowercase, no hyphens | `com.example.accountservice.account.domain` |
| Constants | `UPPER_SNAKE_CASE` | `MAX_TRANSACTIONS_PER_PAGE` |
| Methods/fields | `camelCase` | `getAccountId()`, `pullDomainEvents()` |
| Enum constants | `UPPER_SNAKE_CASE` | `AccountStatus.ACTIVE` |

| Kind | Location | Class-name pattern | Example |
|------|------|-------------|------|
| Aggregate Root | `domain/` | domain noun | `Account` |
| Child Entity | `domain/` | domain noun | `Transaction` |
| Value Object | `domain/` | concept name, `record` | `Money` |
| Domain Event | `domain/` | past tense, `record` | `MoneyDepositedEvent` |
| Repository interface | `domain/` | `<Aggregate>Repository` | `AccountRepository` |
| Repository implementation | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl` | `AccountRepositoryImpl` |
| Spring Data JPA interface | `infrastructure/persistence/` | `<Aggregate>JpaRepository` | `AccountJpaRepository` |
| Command Service | `application/command/` | `<Verb><Noun>Service` | `CreateAccountService` |
| Query Service | `application/query/` | `Get<Noun>Service` | `GetAccountService` |
| Command | `application/command/` | `<Verb><Noun>Command`, `record` | `DepositCommand` |
| Result | `application/{command,query}/` | `<Verb><Noun>Result`, `record` | `GetAccountResult` |
| Domain Event Handler | `application/event/` | `<DomainEvent>Handler`, implements `outbox.OutboxEventHandler` | `AccountCreatedEventHandler` |
| Technical Service interface | `application/service/` | `<Concern>Service` | `NotificationService` |
| Technical Service implementation | `infrastructure/<concern>/` | `<Concern>ServiceImpl` | `NotificationServiceImpl` (`account/infrastructure/notification/`) |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller` | `AccountController` |
| Interface DTO | `interfaces/rest/` | `<Verb><Noun>Request`, `record` | `DepositRequest` |
| Scheduler | `<domain>/infrastructure/scheduling/` | `<Concern>Scheduler` | `InterestPaymentScheduler` |
| Task Controller | `<domain>/interfaces/task/` | `<Verb><Noun>TaskController`, implements `taskqueue.TaskHandler` | `PayInterestTaskController` |

---

## Placement criteria for shared infrastructure

Both `common/` (ID-generation utilities, etc.) and `config/` (per-concern configuration), required by the root, exist as top-level packages. Placement criteria:

| Directory | Contents | Related documents |
|---------|----------|----------|
| `common/` | `IdGenerator`, `CorrelationIdFilter`/`RequestLoggingInterceptor`/`RateLimitFilter`/`GlobalExceptionHandler` (`web/`), `SecretService` (`service/`+`infrastructure/`) | [aggregate-id.md](aggregate-id.md), [cross-cutting-concerns.md](cross-cutting-concerns.md), [secret-manager.md](secret-manager.md) |
| `config/` | `@ConfigurationProperties` classes such as `AwsProperties`, `SesProperties`, `JwtProperties`, plus `SecurityConfig`, `WebConfig`, `OpenApiConfig` | [config.md](config.md), [bootstrap.md](bootstrap.md) |
| `outbox/` | `OutboxEvent`, `OutboxWriter`, `OutboxPoller`, `OutboxConsumer`, `OutboxEventHandler` | [domain-events.md](domain-events.md) |
| `taskqueue/` | `TaskOutboxEntry`, `TaskOutboxWriter`, `TaskOutboxPoller`, `TaskConsumer`, `TaskHandler` | [scheduling.md](scheduling.md) |

These are project-wide shared code that doesn't belong to any specific domain (`account`/`card`/`auth`), so they're placed outside the domain packages, as top-level packages directly under `com.example.accountservice`. Conversely, a Technical Service like `notification`, which only one domain actually uses, is placed inside that domain (`account/infrastructure/notification/`) rather than as shared infrastructure — it isn't promoted to the top level until multiple domains actually share it.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — per-layer responsibilities and dependency direction
- [repository-pattern.md](repository-pattern.md) — Repository interface/implementation separation
- [tactical-ddd.md](tactical-ddd.md) — Aggregate/Entity/Value Object placement
