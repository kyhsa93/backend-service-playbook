# CQRS Pattern (Spring Boot)

> For the framework-agnostic principles, see the root [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md). This repository does not go as far as adopting Handler/Bus-based CQRS (`CommandBus`/`QueryBus`) — it only applies the **lightweight CQRS** from [layer-architecture.md](layer-architecture.md) (separating Command Service / Query Service classes), because the number of use cases is small enough that the extra infrastructure of a Handler-based approach (a Bus, a Handler registry) isn't yet justified.

## Class separation — the current implementation is correct

Write/read use cases are separated into the `account/application/command/` and `account/application/query/` packages, each declaring different transaction attributes.

```java
// application/command/CreateAccountService.java — write
@Service
@RequiredArgsConstructor
public class CreateAccountService {
    private final AccountRepository accountRepository;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.saveAccount(account);   // @Transactional — Account save + Outbox write, one transaction
        return new CreateAccountResult(...);       // returned right after saving — draining is handled asynchronously by OutboxPoller/OutboxConsumer (see domain-events.md)
    }
}
```

```java
// application/query/GetAccountService.java — read
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountQuery accountQuery;

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        return new GetAccountResult(...);
    }
}
```

`@Transactional(readOnly = true)` lets Hibernate skip dirty checking, reducing the overhead of a read-only transaction — effectively, Spring documents CQRS's "separation of write/read responsibility" at the level of transaction attributes.

That the Command Service delegates logic to the Aggregate (`Account.create()`) rather than performing business rules itself also matches the root principle.

---

## `GetAccountService`/`GetTransactionsService` — separated via the `AccountQuery` interface

Root principle: a Query Service must use **a dedicated Query interface, not the write-side Repository**. This is so read-optimized queries can run without reconstituting the Aggregate. The root [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md) names this interface `<Aggregate>Query` (e.g. `OrderQuery`) — this repository follows the same convention and names it `AccountQuery` (not `AccountQueryRepository` — the name "Repository" evokes the write model and conflicts with the principle).

Both `GetAccountService`/`GetTransactionsService` inject `AccountQuery` (application/query), not the write-side `AccountRepository`:

```java
// application/query/AccountQuery.java — the Query interface, actual code
public interface AccountQuery {
    AccountsWithCount findAccounts(AccountFindQuery query);
    TransactionsWithCount findTransactions(String accountId, int page, int take);
}
```

```java
// application/query/GetAccountService.java — actual code
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountQuery accountQuery;   // narrow read-only interface

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        return new GetAccountResult(/* ... */);
    }
}
```

```java
// application/query/GetTransactionsService.java — actual code
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetTransactionsService {
    private final AccountQuery accountQuery;   // the same narrow interface as GetAccountService

    public GetTransactionsResult getTransactions(String accountId, String requesterId, int page, int take) {
        accountQuery.findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "Account not found."));
        TransactionsWithCount result = accountQuery.findTransactions(accountId, page, take);
        return new GetTransactionsResult(/* ... */, result.count());
    }
}
```

`AccountRepositoryImpl` (infrastructure) implements **both** `AccountRepository` (domain, write) and `AccountQuery` (application, read) — rather than creating a separate Query-only implementation class, the existing implementation shares the intersection of the two interfaces (`findAccounts`/`findTransactions`):

```java
// infrastructure/persistence/AccountRepositoryImpl.java — actual code (excerpt)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        // assembles and runs list/count JPQL via buildJpql() — see repository-pattern.md "dynamic filter pattern"
    }
    // ... the remaining write methods of AccountRepository (saveAccount/delete, etc.)
}
```

DI is disambiguated automatically by Spring based on the declared type at the injection point — when `GetAccountService`/`GetTransactionsService` inject the `AccountQuery` type, they get wired to the single bean implementing that interface (`AccountRepositoryImpl`), but write methods like `saveAccount`/`delete` are never exposed on the `AccountQuery` type in the first place, so the two Query Services cannot call those methods at compile time.

**Why this separation matters:**
- Changes to `AccountRepository` (write) — e.g. a change in save strategy — don't affect `AccountQuery`'s contract at all, reducing coupling.
- When mock-testing a Query Service, only the narrow interface (without write methods like `saveAccount`/`delete`) needs to be mocked, which makes the Application unit tests in [testing.md](testing.md) clearer.
- `harness/src/rules/CqrsQueryPurity.java` (the `cqrs-query-purity` rule) automatically catches any reference to a write-side Repository type in files under `application/query/` — a rule ported from the nestjs harness's `cqrs-pattern` evaluator. Unlike a generic `package-structure` check that only verifies the `application/query` directory exists, this rule inspects the actual type references inside those files.

### Extension pattern — if a projection-specific implementation becomes necessary

If the read path wants to select only the columns needed for the response schema, instead of loading the full JPA entity (`AccountJpaEntity`, its associated `MoneyEmbeddable`, etc.), there is also the option of implementing `AccountQuery` via a separate `AccountQueryImpl` (writing projection JPQL directly with an `EntityManager`) rather than `AccountRepositoryImpl`:

```java
// infrastructure/persistence/AccountQueryImpl.java — example if a separate implementation is introduced
@Repository
@RequiredArgsConstructor
public class AccountQueryImpl implements AccountQuery {
    private final EntityManager em;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        // it's also possible to query only the needed columns via a projection query, then assemble via Account.reconstitute()
    }
}
```

At this repository's current scale (8 Aggregate fields), having `AccountRepositoryImpl` implement both interfaces together is judged sufficient, so this separation is not introduced — if multiple implementations become necessary, disambiguate with `@Qualifier` at Spring bean registration.

---

## Command and Query objects

```java
// application/command/DepositCommand.java
public record DepositCommand(String accountId, String requesterId, long amount) {}

// application/query/GetTransactionsResult.java — Result is the API response schema
public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(String transactionId, String type, MoneyResult amount, LocalDateTime createdAt) {}
    public record MoneyResult(long amount, String currency) {}
}
```

Representing Command/Result objects as Java `record`s gets immutability and `equals`/`hashCode` for free. Interface DTOs like `interfaces/rest/DepositRequest.java` are also defined as records and converted to a Command in the Controller.

---

## EventHandler — implemented via the Outbox

The role that a CQRS `event/` package would play is filled in this repository by the `*EventHandler` classes (e.g. `AccountCreatedEventHandler`) in `account/application/event/`. Rather than `@EventListener`, they implement the `outbox/OutboxEventHandler` interface, and `OutboxConsumer` routes events received from SQS to the right handler by type. See [domain-events.md](domain-events.md) for the detailed path (the Repository's Outbox write, the timing of `OutboxPoller`'s SQS publish).

---

## Base architecture (Service separation) vs. Handler-based CQRS

| | This repository (lightweight CQRS) | Handler-based CQRS (not adopted) |
|---|---|---|
| Write entry point | `XxxCommandService.method()` | `CommandHandler.execute()` |
| Read entry point | `XxxQueryService.method()` (`GetAccountService`/`GetTransactionsService` use `AccountQuery`) | `QueryHandler.execute()` |
| Routing | Controller calls the Service directly | CommandBus/QueryBus |
| Use-case unit | A method on a Service | One Handler class |
| Fits at this scale | The current Account domain (6 use cases) | When use cases grow large enough that Services become bloated |

If the number of use cases grows to the point where `AccountController` ends up injecting too many Services, consider switching to `application/command/*CommandHandler.java` plus a Spring-managed `Map<Class<?>, CommandHandler<?>>` registry.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — the base structure of Command/Query Services
- [domain-events.md](domain-events.md) — EventHandler and the Outbox pattern
- [repository-pattern.md](repository-pattern.md) — the Repository pattern (write-only)
- [testing.md](testing.md) — Application unit testing of Command/Query Services
