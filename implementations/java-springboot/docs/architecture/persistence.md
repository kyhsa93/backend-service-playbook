# Persistence Pattern (Spring Boot / Spring Data JPA)

> For the framework-agnostic principles, see the root [persistence.md](../../../../docs/architecture/persistence.md).

## Transaction propagation — `@Transactional` replaces the Unit of Work

The root describes a manual TransactionManager based on `AsyncLocalStorage`/`ThreadLocal`, but Spring replaces this entirely with declarative `@Transactional` (based on an AOP proxy). There's no need to implement a separate TransactionManager class by hand.

```java
// account/infrastructure/persistence/AccountRepositoryImpl.java — actual code (excerpt)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    @Transactional
    public void saveAccount(Account account) {
        // Saving the Account + writing to the Outbox run as one physical transaction (see domain-events.md)
    }
}
```

The moment `@Transactional` is entered, Spring begins a transaction; it commits on a normal return, and rolls back on an unchecked exception (a subclass of `RuntimeException`). Since the transaction context propagates via thread-local, calling multiple Repositories within the same call stack automatically joins the same transaction — a Spring proxy plays the role the root's `AsyncLocalStorage` plays.

**The sole physical transaction boundary is this `Repository.save*()` method.** The Command Service itself (e.g. `CreateAccountService`) carries no `@Transactional` at all — once the save (and the Outbox write inside it) finishes, the Command Service returns immediately, and the Outbox drain is handled later (up to 1 second afterward) by a completely separate process (`OutboxPoller`/`OutboxConsumer`, see [domain-events.md](domain-events.md)). Re-adding `@Transactional` to a Command Service is a regression.

---

## `REQUIRES_NEW` — isolating the notification-send transaction

`account/infrastructure/notification/NotificationServiceImpl` is the only place in this repository that actually customizes the propagation attribute:

```java
// account/infrastructure/notification/NotificationServiceImpl.java — actual code
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final SesClient sesClient;
    private final SentEmailRepository sentEmailRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(String accountId, String eventType, String recipient, String subject, String body) {
        SendEmailResponse response = sesClient.sendEmail(/* ... */);
        SentEmail sentEmail = SentEmail.create(accountId, eventType, recipient, subject, response.messageId());
        sentEmailRepository.save(sentEmail);
    }
}
```

**Why `REQUIRES_NEW`**: this method is called by `OutboxConsumer.handleMessage()`, through an `outbox/OutboxEventHandler` implementation such as `AccountCreatedEventHandler` — by that point the account-save transaction has already committed and finished, and receiving from SQS happens in a completely separate process/thread (see [domain-events.md](domain-events.md)). Unlike `OutboxPoller.poll()`, `OutboxConsumer`'s dispatch path is **not `@Transactional`** — each message is handled individually and multiple messages are never bundled into one physical transaction, so at the moment `sendEmail()` is called there is no "parent batch transaction" to contaminate in the first place. `REQUIRES_NEW` is kept anyway as a defensive measure: this Technical Service, responsible for the SES call plus the `SentEmail` record, must always commit/roll back in its own physical transaction regardless of the caller's transaction state — this principle is explicitly enforced so that even if someone later adds `@Transactional` back to a call site like `AccountSuspendedEventHandler.handle()` or to `OutboxConsumer`'s dispatch path (e.g. as an optimization bundling multiple messages into one transaction), `REQUIRES_NEW` continues to block the regression where an SES call failure would contaminate that transaction into rollback-only.

| Propagation attribute | Behavior | Where it's used in this repository |
|---|---|---|
| `REQUIRED` (default) | Joins an existing transaction if one exists, otherwise starts a new one | Repository implementations like `AccountRepositoryImpl.saveAccount()`, `OutboxPoller.poll()` |
| `REQUIRES_NEW` | Always starts a new physical transaction, suspending any existing one | `NotificationServiceImpl.sendEmail()` |
| `readOnly = true` | Skips dirty checking/flush (a performance optimization, not a propagation attribute) | Every Query Service |

The Command Service itself (`CreateAccountService`, etc.) carries no `@Transactional` — see the "Transaction propagation" section above.

---

## Bundling multiple Repository saves into one transaction — a real example (transfer between accounts)

A representative use case where a single `saveAccount()` isn't enough is a transfer between accounts — if the withdrawal-account save and the deposit-account save each commit independently, a failure mode arises where "the withdrawal took effect but the deposit was lost." A dedicated method is added to `AccountRepository`, keeping the transaction boundary in the Repository as always (never in the Command Service):

```java
// account/domain/AccountRepository.java — actual code
public interface AccountRepository {
    void saveAccount(Account account);

    // saves both the source and target Account as one physical transaction.
    void saveAccounts(Account source, Account target);
    // ...
}

// account/infrastructure/persistence/AccountRepositoryImpl.java — actual code
@Override
@Transactional
public void saveAccounts(Account source, Account target) {
    saveAccountInternal(source);
    saveAccountInternal(target);
}

// account/application/command/TransferService.java — actual code, no @Transactional
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;

    public TransferResult transfer(TransferCommand command) {
        // ... load source/target, judge via TransferEligibilityService, call withdraw/deposit ...
        accountRepository.saveAccounts(source, target);
        // ...
    }
}
```

---

## Common Entity columns — `createdAt`/`updatedAt`/`deletedAt`

`Account` (domain, a pure object) and its counterpart `AccountJpaEntity` (infrastructure, JPA mapping) both carry all three columns:

```java
@Column(nullable = false)
private LocalDateTime createdAt;

@Column(nullable = false)
private LocalDateTime updatedAt;

@Column
private LocalDateTime deletedAt;
```

`createdAt`/`updatedAt` are set manually by domain methods (`create()`, `deposit()`, etc.) via `LocalDateTime.now()`. For automating this with `@CreatedDate`/`@LastModifiedDate` (Spring Data JPA Auditing), see the common-columns section of [repository-pattern.md](repository-pattern.md).

---

## Soft delete — genuinely wired up

Root principle: deletion defaults to soft delete, recording a `deletedAt` timestamp, rather than a hard delete, and `deletedAt IS NULL` must be applied by default on lookups.

`Account` has a `deletedAt` column, and its lookup queries genuinely apply a `deletedAt IS NULL` condition:

```java
// AccountRepositoryImpl — actual code, lookups are correctly filtered
AccountsWithCount findAccounts(AccountFindQuery query) {
    // buildJpql() always starts with "WHERE a.deletedAt IS NULL" — single-record lookups also reuse this method via take:1 (see repository-pattern.md)
}
```

**The wiring that genuinely sets `deletedAt` is in place.** `Account.close()` only changes the status to `status = CLOSED` — it is not a deletion. "Closing an account" (a business status transition) and "deleting the record" (an administrative action) are distinct concepts, each implemented via its own domain method/Repository method/use case.

### The wiring — an Aggregate method + a Repository method + an Application use case

```java
// domain/Account.java — actual code
public void delete() {
    if (this.status != AccountStatus.CLOSED) {
        throw new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE, "Only a closed account can be deleted.");
    }
    if (this.deletedAt != null) {
        throw new AccountException(AccountException.ErrorCode.ACCOUNT_ALREADY_DELETED, "This account has already been deleted.");
    }
    this.deletedAt = LocalDateTime.now();
}
```

```java
// domain/AccountRepository.java — actual code
void deleteAccount(String accountId);
```

```java
// AccountRepositoryImpl — actual code
@Override
@Transactional
public void deleteAccount(String accountId) {
    jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId).ifPresent(entity -> {
        Account account = AccountMapper.toDomain(entity);
        account.delete();                          // validates the invariant via the domain method, then sets deletedAt
        AccountMapper.updateEntity(entity, account);
        jpaRepository.save(entity);
    });
}
```

```java
// application/command/DeleteAccountService.java — actual code
@Service
@RequiredArgsConstructor
public class DeleteAccountService {
    private final AccountRepository accountRepository;

    public void delete(DeleteAccountCommand command) {
        accountRepository
                .findAccounts(new AccountFindQuery(0, 1, command.accountId(), command.requesterId(), null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        accountRepository.deleteAccount(command.accountId());
    }
}
```

`AccountController`'s `DELETE /accounts/{accountId}` calls this use case. Ownership verification (calling `findAccounts` with `take: 1`) is handled in the Application layer, while validating the invariant that "only a CLOSED account can be deleted" is handled in the Domain layer (`Account.delete()`).

**Child entities also need soft delete propagated**: if `deletedAt` needs to propagate to `Transaction` (a child entity) as well, either `Account.delete()` must reflect the deletion onto its `Transaction`s internally, or the Repository must explicitly handle the ordering. Currently `Transaction` has no `deletedAt` column at all — whether to hide transaction history when an account is deleted is a decision that depends on audit requirements, and this repository currently chooses to keep transaction history intact.

---

## Migrations — managed by Flyway

Root principle: schema changes are managed via migration files. Automatic schema synchronization like `ddl-auto: update`/`synchronize` is **for development environments only**. This example adopts Flyway to follow this principle.

```groovy
// build.gradle
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
```

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # only verifies that migrations and entity mapping match. No automatic changes
  flyway:
    enabled: true
    locations: classpath:db/migration
```

```sql
-- src/main/resources/db/migration/V1__create_accounts.sql
CREATE TABLE accounts (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    amount BIGINT,
    currency VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    CONSTRAINT uk_accounts_account_id UNIQUE (account_id)
);

-- V2__create_transactions.sql, V3__create_outbox.sql, V4__create_sent_email.sql — the remaining 3 tables follow the same approach
```

Why the `amount`/`currency` column names have no prefix like `balance_`/`transaction amount`: both `AccountJpaEntity.balance`/`TransactionJpaEntity.amount` (infrastructure) are `@Embedded MoneyEmbeddable` and don't use `@AttributeOverride`, so Hibernate uses `MoneyEmbeddable`'s own field names (`amount`, `currency`) directly as column names. Since each is in its own separate table, the names overlapping causes no problem.

`ddl-auto: validate` never lets Hibernate change the schema — it only checks that the entity mapping matches the actual schema. On a mismatch, startup fails (fail-fast), catching a missing migration before deployment. Booting the app against an empty DB confirms that Flyway applies all 4 migrations automatically and that `ddl-auto: validate` passes.

**The test environment is an exception**: `AccountControllerE2ETest`/`NotificationE2ETest`'s `@DynamicPropertySource` using `ddl-auto: create-drop` + `spring.flyway.enabled: false` falls within the "development/test only" allowance the root explicitly states — since Testcontainers spins up a fresh container for every test run, automatic schema generation is actually appropriate there, and enabling Flyway and create-drop at the same time would duplicate schema creation, so Flyway is disabled in tests.

---

## Principle summary

| Principle | State in this repository |
|---|---|
| Transactions propagate implicitly, context-local | Replaced by the `@Transactional` AOP proxy — followed |
| Isolate side effects with `REQUIRES_NEW` | `NotificationServiceImpl.sendEmail()` — followed |
| Every table has `createdAt`/`updatedAt`/`deletedAt` | `Account`/`AccountJpaEntity` carry all 3 columns — followed |
| Deletion defaults to soft delete | Wired via `Account.delete()` + `AccountRepository.delete()` + `DeleteAccountService` — followed |
| Schema changes are managed via migrations | Flyway (`db/migration/`) + `ddl-auto: validate` — followed |

## Harness verification

`harness/src/rules/SoftDeleteFilter.java` (rule: `soft-delete-filter`) checks that a `*RepositoryImpl.java`'s find methods, when querying a `*JpaEntity` that has a `deletedAt` column, include `deletedAt IS NULL` (or an equivalent filter) — catching the regression where a hard-deleted-looking row leaks through despite being soft-deleted. If an Entity class has a Hibernate `@SQLRestriction`/`@Where` global filter, that's accepted instead and the RepositoryImpl check is skipped (passing via either mechanism is fine). Entities with no `deletedAt` column (Card/Payment/Refund, etc., which don't yet have a deletion use case) are naturally excluded from this check.

`harness/src/rules/NoOrmAutoSyncInProdConfig.java` (rule: `no-orm-autosync-in-prod-config`) is the regression guard for the "migrations — managed by Flyway" principle above. It fails the build if `spring.jpa.hibernate.ddl-auto` in either `src/main/resources/application.yml` (the base file, no profile) or `application-prod.yml` (the prod profile) is set to `update`/`create`/`create-drop`. The reason the base file is also checked: if `SPRING_PROFILES_ACTIVE` is missing, the base file's value applies to production as-is. A file with no `ddl-auto` key at all means there's no automatic schema sync, so it passes. E2E test code (Java) using `create-drop` via `@DynamicPropertySource` isn't YAML, so it was never a target for parsing in the first place — this falls under the "the test environment is an exception" case described above.

### Related documents

- [repository-pattern.md](repository-pattern.md) — the Repository's `delete<Noun>` method
- [domain-events.md](domain-events.md) — the Outbox write is also handled in the same transaction
- [config.md](config.md) — branching `ddl-auto` by environment
