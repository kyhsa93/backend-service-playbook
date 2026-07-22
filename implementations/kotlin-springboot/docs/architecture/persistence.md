# Persistence Patterns — Kotlin Spring Boot

> For the framework-agnostic principles, see [root persistence.md](../../../../docs/architecture/persistence.md).

## Transaction propagation — `@Transactional` (Spring replaces the root's ThreadLocal pattern)

The root specifies implicitly propagating the transaction client via a language-specific context-local store (Node's `AsyncLocalStorage`, Java/Kotlin's `ThreadLocal`). In Spring/JPA, this mechanism is already built into the framework as **`@Transactional` + a thread-bound connection**, so there's no need to implement a separate `TransactionManager` class yourself.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository {
    @Transactional
    override fun saveAccount(account: Account) {
        jpaRepository.save(/* ... */)                    // saving the Account
        // ... saving the child Transaction ...
        outboxWriter.saveAll(account.pullDomainEvents())  // same thread = automatically reuses the same transaction's JDBC connection
    }
}
```

`@Transactional` is on the `Repository`'s save method, not the Command Service — because this is precisely the boundary that ties saving the Account and writing to the Outbox into a single physical transaction (see [domain-events.md](domain-events.md)).

When execution enters a `@Transactional` method, Spring AOP starts a transaction and binds a connection to the current thread (`TransactionSynchronizationManager`), and every Repository method called on the same thread automatically uses the same connection/transaction — Spring internally performs the equivalent of the root's `getClient()` pattern.

**A real example of tying multiple Repositories into one transaction** — a transfer between accounts (Transfer) is the representative use case (if the withdrawal-account save and the deposit-account save each commit independently, you get the failure mode "the withdrawal was applied but the deposit was lost"). `TransferService` (the Command Service) itself has no `@Transactional` — the boundary still lives on the Repository:

```kotlin
// domain/AccountRepository.kt — actual code
interface AccountRepository {
    fun saveAccount(account: Account)

    // Saves both the source/target Accounts in a single physical transaction.
    fun saveAccounts(source: Account, target: Account)
    // ...
}

// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
@Transactional
override fun saveAccounts(source: Account, target: Account) {
    saveAccountInternal(source)
    saveAccountInternal(target)
}

// application/command/TransferService.kt — actual code, no @Transactional
@Service
class TransferService(
    private val accountRepository: AccountRepository,
) {
    fun transfer(command: TransferCommand): TransferResult {
        // ... load source/target, judge via TransferEligibilityService, call withdraw/deposit ...
        accountRepository.saveAccounts(source, target)
        // ...
    }
}
```

A Query Service is distinguished by `@Transactional(readOnly = true)` — it skips Hibernate's dirty checking and uses a read-only connection to optimize.

```kotlin
// application/query/GetAccountService.kt — actual code
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) { /* ... */ }
```

---

## Common Entity columns — currently repeated declarations, could be improved via `@MappedSuperclass`

```kotlin
// domain/Account.kt, domain/Transaction.kt — actual code (each Entity declares this repeatedly)
@Column(nullable = false)
var createdAt: LocalDateTime = LocalDateTime.now()
    private set

@Column(nullable = false)
var updatedAt: LocalDateTime = LocalDateTime.now()
    private set

@Column
var deletedAt: LocalDateTime? = null
    private set
```

Both `Account` and `Transaction` declare these three columns independently — there's no reusable `@MappedSuperclass` inheritance. At the current scale of just 2 Aggregates the duplication isn't significant, but as the number of domains grows, extracting a common base as shown below would match the root's "inherit from a common BaseEntity" principle.

```kotlin
// common/BaseEntity.kt — proposal
@MappedSuperclass
abstract class BaseEntity {
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Column
    var deletedAt: LocalDateTime? = null
        protected set

    fun markUpdated() { updatedAt = LocalDateTime.now() }
    fun markDeleted() { deletedAt = LocalDateTime.now() }
}
```

```kotlin
// domain/Account.kt — if inheriting from BaseEntity
@Entity
@Table(name = "accounts")
class Account protected constructor() : BaseEntity() { /* createdAt/updatedAt/deletedAt removed, replaced by inheritance */ }
```

The reason it's left open as `protected set` is so the subclass Entity (`Account`) can only change the value through an intention-revealing method like `markUpdated()`/`markDeleted()` — it never assigns to the field directly.

---

## Soft Delete — fully wired

The `deletedAt` column exists on `Account`, and `AccountRepository.deleteAccount(accountId)` provides the actual execution path. `Transaction` has ledger-like semantics that are immutable after creation, so there's no delete use case at all, and no `deletedAt` column either — with no delete path, there's no hard-delete risk. `Card`/`Payment`/`Refund` also have no delete use case yet, so they have no `deletedAt` column — the harness's `soft-delete-filter` rule (below) only applies its filter check to an Entity that actually has a `deletedAt` column, excluding any Entity that lacks the column entirely.

```kotlin
// domain/AccountRepository.kt — actual code
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

```kotlin
// domain/Account.kt — actual code. soft delete is only allowed from the CLOSED state
fun markDeleted() {
    if (status != AccountStatus.CLOSED) throw DeleteRequiresClosedAccountException()
    deletedAt = LocalDateTime.now()
    updatedAt = deletedAt!!
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
@Transactional
override fun deleteAccount(accountId: String) {
    val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
    account.markDeleted()
    jpaRepository.save(account)
}
```

```kotlin
// the query already applies a deletedAt IS NULL condition — actual code
// (findAccounts is the identical signature shared by both AccountRepository/AccountQuery)
private fun buildJpql(query: AccountFindQuery, count: Boolean): String {
    val select = if (count) "SELECT COUNT(a)" else "SELECT a"
    val sb = StringBuilder("$select FROM AccountJpaEntity a WHERE a.deletedAt IS NULL")
    /* ... remaining dynamic filter conditions ... */
    return sb.toString()
}
```

**`close()` (a state transition) and soft delete are separated into distinct lifecycle events.** `Account.close()` only changes the state to `AccountStatus.CLOSED` and never touches `deletedAt` — a `CLOSED` account must still be queryable via `GetAccountService` (since every query conditions on `deletedAt IS NULL`, if `close()` also set `deletedAt`, there'd be no way to query the account again right after it's closed). Deletion instead exists as a separate use case, `account/application/command/DeleteAccountService.kt` (`DELETE /accounts/{accountId}`), and `Account.markDeleted()` enforces the rule "only an already-CLOSED account can be deleted" at the domain level — trying to delete an active account directly throws `DeleteRequiresClosedAccountException` (400).

Method naming (`delete<Noun>`) matches the root convention — the Repository uses `findAccounts`/`saveAccount` naming. See [repository-pattern.md](repository-pattern.md) for details.

---

## Migrations — managed via Flyway

Hibernate's automatic schema sync is a feature the root pins down as "development-environment only." This example adopts Flyway to follow that principle.

```kotlin
// build.gradle.kts
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # schema validation only — changes only go through migration files
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

-- V2__create_transactions.sql, V3__create_outbox_events.sql, V4__create_sent_emails.sql — the remaining 3 tables follow the same approach
```

Why `amount`/`currency` have no prefix: `Account.balance`/`Transaction.amount` are `@Embedded Money`, and since `@AttributeOverride` isn't used, Hibernate uses `Money`'s field names as the column names as-is — this migration just carries the existing (not-yet-fixed) schema over as-is. `outbox_events`/`sent_emails` each use their own `id` identity PK + a unique `event_id`/`sent_email_id` combination (a subtly different entity design from the Java version's `outbox`/`sent_email` — the Kotlin side separates the Outbox row's own identifier from the internal DB PK).

Actually booted the app against an empty DB and confirmed Flyway auto-applies all 4 migrations and that `ddl-auto: validate` passes.

**The test environment is an exception**: `AccountControllerE2ETest`/`NotificationE2ETest`'s `@DynamicPropertySource` using `ddl-auto: create-drop` + `spring.flyway.enabled: false` falls within the "development/test only" allowance the root explicitly states — since Testcontainers spins up a fresh container on every test run, automatic schema creation is actually appropriate, and turning on Flyway at the same time would create the schema twice, so Flyway is turned off in tests.

---

## Principle summary

- **Transactions are declared with `@Transactional`** — Spring replaces the root's manual ThreadLocal propagation. Command uses `@Transactional`, Query uses `@Transactional(readOnly = true)`.
- **Common columns should be extracted into a `@MappedSuperclass` BaseEntity** to remove duplication — currently repeated per-Entity.
- **Soft Delete is fully wired** — `AccountRepository.deleteAccount()` + `Account.markDeleted()` (only allowed from CLOSED) + `DeleteAccountService`/`DELETE /accounts/{accountId}` (details in [repository-pattern.md](repository-pattern.md)).
- **Flyway migrations are adopted** — `ddl-auto: validate` + `db/migration/`.

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository interface/implementation separation, delete method design
- [layer-architecture.md](layer-architecture.md) — the transaction boundary and layer roles
- [testing.md](testing.md) — using `ddl-auto: create-drop` in tests
- harness `soft-delete-filter` rule (`../../harness/README.md`) — mechanically verifies that a query on an Entity with a `deletedAt` column actually filters on that column (or has a global `@SQLRestriction`/`@Where` filter)
- harness `no-orm-autosync-in-prod-config` rule (`../../harness/README.md`) — mechanically fails if `spring.jpa.hibernate.ddl-auto` in `src/main/resources/application*.yml` isn't `validate`/`none` (i.e. if `update`/`create`/`create-drop` is left in a production configuration)
