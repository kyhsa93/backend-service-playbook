# Repository Pattern (Spring Boot / Spring Data JPA)

> For the framework-agnostic principles, see the root [repository-pattern.md](../../../../docs/architecture/repository-pattern.md).

## Interface (domain) + implementation (infrastructure) separation

Instead of TypeScript's `abstract class`, a Java `interface` is used as the DI token — in Java/Spring, an interface itself is a valid DI type at runtime, so no separate workaround is needed.

```java
// account/domain/AccountRepository.java — actual code, entirely Spring-independent
package com.example.accountservice.account.domain;

public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);
    void saveAccount(Account account);
    void deleteAccount(String accountId);
    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
```

```java
// account/infrastructure/persistence/AccountRepositoryImpl.java — actual code (excerpt)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        String listJpql = buildJpql(query, false);
        var listQuery = em.createQuery(listJpql, AccountJpaEntity.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(listQuery, query);
        List<Account> accounts = listQuery.getResultList().stream().map(AccountMapper::toDomain).toList();

        String countJpql = buildJpql(query, true);
        var countQuery = em.createQuery(countJpql, Long.class);
        applyParams(countQuery, query);
        long count = countQuery.getSingleResult();

        return new AccountsWithCount(accounts, count);   // AccountJpaEntity -> pure domain Account
    }
}
```

An Application Service injects the `AccountRepository` interface type, and Spring auto-binds the single implementation on the classpath (`AccountRepositoryImpl`, `@Repository`). If multiple implementations become necessary, disambiguate with `@Qualifier`.

---

## Two-tier Repository — the domain Repository and the Spring Data JPA Repository

This repository splits Repository into two tiers:

```java
// infrastructure/persistence/AccountJpaRepository.java — Spring Data auto-generates the implementation
public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long> {
    Optional<AccountJpaEntity> findByAccountId(String accountId);              // for the update-or-insert lookup in saveAccount()
    Optional<AccountJpaEntity> findByAccountIdAndDeletedAtIsNull(String accountId);  // for looking up the soft-delete target in delete()
}
```

`AccountJpaEntity` is the JPA-mapping-only counterpart of `Account` (domain) — see [layer-architecture.md](layer-architecture.md) for how `Account` stays framework-independent.

| | `AccountRepository` (domain) | `AccountJpaRepository` (infrastructure) |
|---|---|---|
| Role | The lookup/save contract the domain needs | Spring Data JPA's CRUD + derived queries |
| Implementation approach | Manually implemented by `AccountRepositoryImpl` | Auto-implemented by Spring Data parsing the method name |
| Used by | Injected by an Application Service | Used only inside `AccountRepositoryImpl` |

**Why `AccountRepositoryImpl` combines both**: a lookup with dynamically combined filter conditions, like `findAccounts(AccountFindQuery)`, is hard to express with Spring Data's derived query method names alone — so it assembles JPQL directly via `EntityManager` (see "Dynamic filter pattern" below). Conversely, a fixed condition like `findByAccountId` is well served by a Spring Data derived query, so it's delegated to `AccountJpaRepository`. Both approaches are mixed as appropriate within the same class (`AccountRepositoryImpl`).

---

## Repository method naming

Root rule: reads are always unified under a single `find<Noun>s` (plural), and single-record lookups use the `take: 1` + extract-the-first-result pattern. Saves are `save<Noun>`, deletes are `delete<Noun>`.

`AccountRepository` follows this rule exactly:

```java
public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);
    void saveAccount(Account account);
    void deleteAccount(String accountId);   // soft delete — genuinely implemented
    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
```

`AccountsWithCount`/`TransactionsWithCount` are `record`s carrying both the list and the total count together — the Java-typed version of the root document's `{ orders: Order[]; count: number }` pattern:

```java
// account/domain/AccountsWithCount.java, account/domain/TransactionsWithCount.java — actual code
public record AccountsWithCount(List<Account> accounts, long count) {}
public record TransactionsWithCount(List<Transaction> transactions, long count) {}
```

**A single-record lookup isn't a separate method — it reuses `findAccounts` called with `take: 1`** — following the root's "unify into a single lookup path" principle, no dedicated single-record-lookup method in the style of a Spring Data derived query (like `findByAccountIdAndOwnerId`) is provided. The Application Service calls it with `take: 1` at the call site and extracts the first result itself:

```java
// application/command/DepositService.java — actual code
Account account = accountRepository
        .findAccounts(new AccountFindQuery(0, 1, command.accountId(), command.requesterId(), null))
        .accounts().stream().findFirst()
        .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
```

This pattern applies uniformly across the write side (`SuspendAccountService`/`WithdrawService`/`DepositService`/`DeleteAccountService`/`CloseAccountService`/`ReactivateAccountService`), the read side (`GetAccountService`/`GetTransactionsService`, via `AccountQuery`), and even Card BC's `AccountAdapterImpl` (ACL) — no dedicated single-record method is ever added to a Repository/Query interface.

For the same reason, `Transaction` (child entity) list lookups, which used to be two separate methods (`findTransactions`/`countTransactions`), are merged into a single `findTransactions` returning `TransactionsWithCount`.

**Harness verification**: `harness/src/rules/RepositoryNaming.java` (rule: `repository-naming`) checks the method names of `*Repository`/`*Query` interfaces under `domain/`·`application/query/` against a blocklist — catching Spring-Data-derived-query-style names like `findByXxx`, a bare `findAll`, methods starting with `count`, a bare `save`/`delete` (with no target noun), and methods starting with `update` (the root principle that "Repository must never have an update method — look up first, then modify via the Aggregate's domain method, then save via `save<Noun>`"). Implementations in `infrastructure/` and internal Spring Data JPA derived query methods (`AccountJpaRepository.findByAccountId`, etc.) are not checked — these are implementation details where derived-query style is legitimately allowed.

**The `delete<Noun>` method — genuinely implemented as soft delete**: an account being "closed" (`Account.close()` — `status = CLOSED`) and being "deleted" (`Account.delete()` — sets `deletedAt`) are distinct concepts. `delete()` is only allowed on a closed (CLOSED) account, and the domain method `Account.delete()` validates this invariant:

```java
// AccountRepositoryImpl — actual code
@Override
@Transactional
public void deleteAccount(String accountId) {
    jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId).ifPresent(entity -> {
        Account account = AccountMapper.toDomain(entity);
        account.delete();                          // validates the invariant (only CLOSED can be deleted) via the domain method, then sets deletedAt
        AccountMapper.updateEntity(entity, account);
        jpaRepository.save(entity);
    });
}
```

`application/command/DeleteAccountService` at the Application layer first verifies ownership (`accountId`+`ownerId`) before calling this method. The details of soft delete itself (the semantic difference between closing an account with `close()` and deleting it with `delete()`, and the actual `deletedAt` wiring) are covered in [persistence.md](persistence.md).

---

## Common columns — `createdAt`/`updatedAt`/`deletedAt`

`Account` has all three columns, but they aren't extracted into a common base class — `Account` and `Transaction` each declare their own fields redundantly. JPA can extract this via `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener.class)`:

```java
// infrastructure/persistence/BaseAuditable.java — proposal, for reference if introduced
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditable {
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;
}
```

Adding `@EnableJpaAuditing` to the `@SpringBootApplication` class would auto-set `createdAt`/`updatedAt`. This repository currently sets `LocalDateTime.now()` manually inside `Account.create()`/domain methods — switching to Auditing would let the JPA entities (`AccountJpaEntity`/`TransactionJpaEntity`, infrastructure) take over timestamp management. Since `Account`/`Transaction` (domain) are already pure domain, placing `@MappedSuperclass` in infrastructure doesn't conflict with the principle in [layer-architecture.md](layer-architecture.md) — however, to avoid double-managing `updatedAt` alongside the current approach where a domain method sets it directly, one approach or the other should be chosen consistently.

---

## Dynamic filter pattern — assembling JPQL via `EntityManager`

```java
// AccountRepositoryImpl.buildJpql() — actual code
private String buildJpql(AccountFindQuery query, boolean count) {
    StringBuilder sb = new StringBuilder(count
            ? "SELECT COUNT(a) FROM AccountJpaEntity a WHERE a.deletedAt IS NULL"
            : "SELECT a FROM AccountJpaEntity a WHERE a.deletedAt IS NULL");
    if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
    if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
    if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
    if (!count) sb.append(" ORDER BY a.accountId DESC");
    return sb.toString();
}
```

This implements the root's "dynamic filter" principle by adding a condition only when a value is present. The input is typed via `account/domain/AccountFindQuery.java` (a record):

```java
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

**An alternative — Spring Data JPA Specification**: if the number of conditions grows further, using `Specification<Account>` (a wrapper around the JPA Criteria API) instead of assembling a string JPQL is better for type safety and readability:

```java
public interface AccountJpaRepository extends JpaRepository<AccountJpaEntity, Long>, JpaSpecificationExecutor<AccountJpaEntity> {}

public class AccountSpecifications {
    public static Specification<AccountJpaEntity> hasOwnerId(String ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }
}
// call site: jpaRepository.findAll(where(hasOwnerId(id)).and(hasStatus(status)), pageable)
```

At the current scale (3 filters), string assembly is still manageable enough that this repository keeps the `EntityManager` approach.

---

## Cascading saves in the Repository — `saveAccount()` handles child entities together

```java
// AccountRepositoryImpl.saveAccount() — actual code
@Override
@Transactional
public void saveAccount(Account account) {
    AccountJpaEntity entity = jpaRepository.findByAccountId(account.getAccountId())
            .map(existing -> AccountMapper.updateEntity(existing, account))
            .orElseGet(() -> AccountMapper.toNewEntity(account));
    jpaRepository.save(entity);
    List<Transaction> pending = account.pullPendingTransactions();   // the Aggregate returns its pending child entities
    if (!pending.isEmpty()) {
        transactionJpaRepository.saveAll(pending.stream().map(TransactionMapper::toNewEntity).toList());
    }
}
```

A `Transaction` created by `Account.deposit()`/`withdraw()` is temporarily accumulated in `pendingTransactions`, and a single call to `saveAccount()` saves both `Account` and `Transaction` together — the Application Service never saves `Transaction` separately. This realizes the root principle that save responsibility is encapsulated at the Aggregate level.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — Repository interface placement, layer dependency direction
- [persistence.md](persistence.md) — transaction propagation, soft-delete wiring, migrations
- [cqrs-pattern.md](cqrs-pattern.md) — introducing `AccountQuery`
- [domain-events.md](domain-events.md) — the correct pattern of saving the Outbox together in the Repository
