# Layer Architecture (Spring Boot)

> For the framework-agnostic principles, see the root [layer-architecture.md](../../../../docs/architecture/layer-architecture.md).

## Dependency direction

```
interfaces/rest (@RestController)  →  application/{command,query} (@Service)  →  domain (Account, AccountRepository interface)
                                                                                        ↑
                                                                      infrastructure/persistence (@Repository, AccountRepositoryImpl)
```

- An upper layer may depend on a lower layer, but never the reverse.
- `AccountRepositoryImpl` (infrastructure) implements `AccountRepository` (domain), inverting the dependency.
- If an injection point is declared with the `AccountRepository` type, Spring finds the single `@Repository` bean on the classpath that implements it (`AccountRepositoryImpl`) and binds it automatically — no separate DI configuration (like registering a module provider) is needed.

---

## The Domain layer — pure domain, separated from JPA mapping

Root principle: the Domain layer is written as **pure code with no dependency on any framework** (including the ORM).

The actual code of `account/domain/Account.java` never imports `jakarta.persistence.*`:

```java
// account/domain/Account.java — actual code, pure domain
public class Account {
    private String accountId;
    private String ownerId;
    private String email;
    private Money balance;
    private AccountStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    private Account() {}

    public static Account create(String ownerId, String email, String currency) { /* ... */ }

    // used by the Repository implementation to reconstitute from persisted data — never generates domain events
    public static Account reconstitute(String accountId, String ownerId, String email, Money balance,
            AccountStatus status, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) { /* ... */ }
    // ... only domain methods (deposit/withdraw/suspend/reactivate/close/delete)
}
```

Every class in the domain package — `Account`, `Transaction` (child entity), even `Money` (Value Object) — carries no JPA annotations. Persistence mapping is separated into dedicated classes in `infrastructure/persistence/`:

```java
// infrastructure/persistence/AccountJpaEntity.java — JPA-mapping-only, actual code
@Entity
@Table(name = "accounts")
public class AccountJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String accountId;
    @Embedded
    private MoneyEmbeddable balance;   // the JPA-mapping-only counterpart of domain.Money
    @Enumerated(EnumType.STRING)
    private AccountStatus status;      // the enum itself is framework-independent, so it's reused as-is
    // ...
}
```

```java
// infrastructure/persistence/AccountMapper.java — dedicated to conversion, actual code (excerpt)
final class AccountMapper {
    static Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(entity.getAccountId(), entity.getOwnerId(), entity.getEmail(),
                entity.getBalance().toDomain(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getDeletedAt());
    }

    static AccountJpaEntity toNewEntity(Account account) { /* for inserts — no PK */ }
    static AccountJpaEntity updateEntity(AccountJpaEntity entity, Account account) { /* for updates — preserves the PK */ }
}
```

```java
// infrastructure/persistence/AccountRepositoryImpl.java — actual code (excerpt)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    private final AccountJpaRepository jpaRepository;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        // runs JPQL assembled via the EntityManager, then maps via AccountMapper::toDomain — mapping happens only here
    }

    @Override
    @Transactional
    public void saveAccount(Account account) {
        AccountJpaEntity entity = jpaRepository.findByAccountId(account.getAccountId())
                .map(existing -> AccountMapper.updateEntity(existing, account))   // for an existing row, update while preserving the PK
                .orElseGet(() -> AccountMapper.toNewEntity(account));             // for a new one, insert with no PK
        jpaRepository.save(entity);
    }
}
```

**Handling the PK (surrogate key)**: the pure domain `Account` has no numeric PK field at all (no `Long id`) — it only knows `accountId` (the business key). When `save()` looks up an existing row, it queries by `accountId` to obtain the existing `AccountJpaEntity` that already carries a PK, and overwrites only the latest state on top of it via `updateEntity()`, preserving the PK. A new Account is inserted by building an entity with no PK.

`Transaction` (the child entity) is separated the same way — `TransactionJpaEntity` + `TransactionMapper`. Since a `Transaction` is never modified after creation, only an insert-only conversion (`toNewEntity`) exists for it.

This separation requires extra classes and conversion code — `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable`/`AccountMapper`/`TransactionMapper` — but it makes the Domain layer import no framework at all, fully matching the root principle.

---

## The Application layer — Command/Query Services

Writes and reads are separated into `application/command/` and `application/query/`. See [cqrs-pattern.md](cqrs-pattern.md) for details.

```java
// application/command/CreateAccountService.java — actual code
@Service
@RequiredArgsConstructor
public class CreateAccountService {
    private final AccountRepository accountRepository;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.saveAccount(account);   // @Transactional lives on this Repository method — Account save + Outbox write, one transaction (see persistence.md)
        return new CreateAccountResult(/* ... */);   // returned right after saving finishes — the Outbox drain is a separate process (OutboxPoller/OutboxConsumer, see domain-events.md)
    }
}
```

- **Constructor injection — no `@Autowired` needed**: Lombok's `@RequiredArgsConstructor` generates a constructor taking the `final` fields, and Spring 4.3+ auto-injects a class with exactly one constructor even without `@Autowired`.
- **Business logic is delegated to the Aggregate**: `CreateAccountService` only calls `Account.create()` — it never performs balance calculation or status validation itself.

A Query Service is distinguished with `@Transactional(readOnly = true)` — this lets Hibernate skip dirty checking and flushing, reducing read overhead.

```java
// application/query/GetAccountService.java — actual code
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountQuery accountQuery;   // a narrow read-only interface, not the write-side AccountRepository

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        return new GetAccountResult(/* ... */);
    }
}
```

`GetAccountService` depends on `AccountQuery` (application/query, declaring only `findAccounts`/`findTransactions`, with no `saveAccount`/`delete`) — `AccountRepositoryImpl` implements both `AccountRepository` (domain, write) and `AccountQuery` (application, read), and Spring binds the same bean to each injection point according to the declared interface type (`AccountRepository` type vs. `AccountQuery` type). See [cqrs-pattern.md](cqrs-pattern.md) for details. `GetTransactionsService` likewise depends on `AccountQuery`.

---

## The Infrastructure layer

`AccountRepositoryImpl` implements both the Domain's `AccountRepository` interface and the Application's `AccountQuery` interface. Using `EntityManager` (dynamic JPQL) together with `AccountJpaRepository` (Spring Data derived queries) is also only permitted in this layer — Domain/Application never know the JPA API. JPQL targets `AccountJpaEntity` (JPA-mapping-only), never the pure domain `Account`, and results are converted via `AccountMapper`.

```java
// infrastructure/persistence/AccountRepositoryImpl.java — actual code (excerpt)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        String jpql = buildJpql(query, false);   // dynamic condition assembly, see repository-pattern.md
        var q = em.createQuery(jpql, AccountJpaEntity.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        List<Account> accounts = q.getResultList().stream().map(AccountMapper::toDomain).toList();   // JPA entity -> pure domain
        long count = /* a COUNT query with the same conditions */ 0;
        return new AccountsWithCount(accounts, count);
    }
}
```

Transaction propagation is handled by Spring's declarative `@Transactional`, replacing the root's manual `AsyncLocalStorage`/`ThreadLocal` pattern — see [persistence.md](persistence.md) for details.

---

## The Interfaces layer

`interfaces/rest/AccountController` is the entry point for external HTTP requests. It converts requests into Command/Query objects and delegates to the Application Service, and converts errors into HTTP responses via `@ExceptionHandler`.

```java
// interfaces/rest/AccountController.java — actual code (excerpt)
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final CreateAccountService createAccountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAccountResult createAccount(
            Authentication authentication,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        String requesterId = authentication.getName();
        return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) { /* ... */ }
}
```

`requesterId` is extracted from `Authentication` (populated by Spring Security after verifying the JWT) — a client-supplied header value is never trusted. See [authentication.md](authentication.md) for details.

### Interface DTO — a thin wrapper over an Application object

The root expresses this via TypeScript `extends`, but in Java it takes the form of **a one-line conversion that simply carries the record's fields over**:

```java
// interfaces/rest/DepositRequest.java — actual code
public record DepositRequest(long amount) {}

// A single line in the Controller method is the mapping point
depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
```

There's no need to introduce a separate mapping library (MapStruct, etc.) — when the field count is small, calling the record constructor is the clearest option.

---

## Principle summary

| Principle | How it's expressed in this repository | Status |
|---|---|---|
| Only upper layers depend on lower layers | Package structure + `harness.sh`'s `package-structure`/`domain-purity` checks | Enforced |
| Domain is independent of any framework/ORM | `Account`/`Transaction`/`Money` are pure domain; JPA mapping is separated into `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` (infrastructure) | Enforced |
| Repository has separate interface/implementation | `interface` (domain) + a `@Repository` implementation Spring auto-binds (infrastructure) | Enforced |
| Query Service uses only the Query interface | Both `GetAccountService`/`GetTransactionsService` use `AccountQuery` (read-only) | Enforced |
| DI | Constructor injection, Lombok `@RequiredArgsConstructor` | Enforced |

---

## Harness verification

`harness/src/rules/DomainLayerIsolation.java` (rule: `domain-layer-isolation`) checks the import statements of files in `<domain>/domain/` and fails the build if they reference `application/`·`infrastructure/`·`interfaces/` of their own or a sibling domain — a more structural (package-path-based) check than a `domain-purity` rule that hardcodes specific Spring annotation names. `harness/src/rules/InterfaceNoInfrastructure.java` (rule: `interface-no-infrastructure`) fails the build if `interfaces/` (a REST Controller, etc.) imports `infrastructure/` directly — maintaining the `interfaces/rest -> application -> domain` dependency direction requires going through `application/`.

---

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — details of the Aggregate, Entity, Value Object
- [repository-pattern.md](repository-pattern.md) — details of the Repository pattern
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query separation, the Query interface
- [domain-events.md](domain-events.md) — Domain Events, the Outbox pattern
- [persistence.md](persistence.md) — transaction propagation
