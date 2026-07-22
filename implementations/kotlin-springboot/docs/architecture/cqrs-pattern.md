# CQRS Pattern — Kotlin Spring Boot

> For the framework-agnostic principles, see [root cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md).

## Current level of adoption — the basic architecture (Service separation)

Of the two stages the root document describes, this repository's `examples/` implements only the **basic architecture** (Command Service / Query Service separation). The **Handler-based CQRS** the root covers in detail (CommandBus/QueryBus, standalone Handler classes) doesn't exist yet — both are covered below.

```
account/application/
  command/
    CreateAccountService.kt   ← @Service — write
    DepositService.kt
    WithdrawService.kt
    SuspendAccountService.kt
    ReactivateAccountService.kt
    CloseAccountService.kt
  query/
    GetAccountService.kt      ← @Service(readOnly) — read
    GetTransactionsService.kt
```

Each Command Service is one class per use case (the same granularity as NestJS's CommandHandler). Having just one method, like `CreateAccountService.create()`, is the Kotlin/Spring idiom — in Java there'd also be the option of grouping use cases into one class like `OrderService.create()/cancel()/...`, but this repository follows the root's "separate by use case" as-is.

```kotlin
// application/command/CreateAccountService.kt — actual code
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)   // @Transactional — saving the Account + writing to the Outbox, one transaction
        return CreateAccountResult(/* ... */)
        // Ends here — draining the Outbox (OutboxPoller/OutboxConsumer) is performed independently
        // by a separate component. See domain-events.md
    }
}
```

```kotlin
// application/query/GetAccountService.kt — actual code
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) {
    fun getAccount(accountId: String, requesterId: String): GetAccountResult {
        val (accounts, _) = accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId))
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)
        return GetAccountResult(/* ... */)
    }
}
```

`@Transactional(readOnly = true)` is only attached on Query Services — Hibernate skips dirty checking and uses a read-only connection to optimize. The Kotlin syntax itself is the same whether it's Command or Query; a Query Service depends on a separate read-only `AccountQuery` interface rather than `AccountRepository` (the write model) (see below).

---

## A separate Query interface — `AccountQuery`

The root (and the NestJS implementation) specify that a Query Service should use a separate read-only `<Domain>Query` interface rather than the Repository — this compile-time-enforces that a Query Service can never access write methods like `saveAccount`/`deleteAccount`. Following the root convention, this interface lives in `application/query/` under the name `AccountQuery` (the write-side `AccountRepository` stays as-is in `domain/`).

```kotlin
// application/query/AccountQuery.kt — actual code
interface AccountQuery {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

`GetAccountService`/`GetTransactionsService` constructor-inject `AccountQuery`, not `AccountRepository`. There is only one implementation — `AccountRepositoryImpl` implements both interfaces. `AccountQuery` reuses **exactly the same signatures** as `AccountRepository`'s (the write model's) `findAccounts`/`findTransactions` (the root `repository-pattern.md`'s `find<Noun>s` unification rule applies to the Query port too) — only `saveAccount`/`deleteAccount` are missing.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
@Repository
class AccountRepositoryImpl(/* ... */) : AccountRepository, AccountQuery {
    // Since AccountRepository and AccountQuery declare findAccounts/findTransactions(query) with the
    // identical signature, these two overrides satisfy both interfaces at once. saveAccount/
    // deleteAccount only exist on AccountRepository (the write model), so a Query Service cannot
    // access them at compile time.
}
```

Card (`CardRepository`/`CardQuery`'s `findCards`) and Payment/Refund (`PaymentRepository`/`PaymentQuery`'s `findPayments`, `RefundRepository`/`RefundQuery`'s `findRefunds`) all follow the same pattern.

Command Services (`CreateAccountService`, etc.) still inject `AccountRepository` (the write model, including `saveAccount`/`deleteAccount`) — physically it's the same implementation and the same table, but the interface split lets each Service access only the methods it needs. This isn't full CQRS (a separate read model/store, running projection-only queries without reconstituting the Aggregate), but it does satisfy the root's core principle that "a Query Service depends on a read-only interface, not the Repository."

---

## When to switch to Handler-based CQRS

| Situation | Recommendation |
|---|---|
| Service classes keep growing as use cases increase | Keep one Service class per use case as now — already broken down to Handler-level granularity |
| Splitting Command/Query into entirely different stores (a CQRS projection DB, etc.) | Introduce a dedicated Query interface + implementation |
| Complex workflows like event sourcing, Sagas, etc. are needed | Consider a dedicated CQRS framework like Axon Framework |

Spring doesn't provide a CommandBus/QueryBus out of the box the way NestJS's `@nestjs/cqrs` does. Alternatives:

1. **Keep the current approach** — the Controller directly calls `@Service` classes. This is sufficient until the use-case count reaches the dozens, and thanks to Kotlin's concise class declarations (a one-line constructor injection), the cost of adding more classes is lower than in Java.
2. **Adopt Axon Framework** — provides `@CommandHandler`/`@QueryHandler` annotations plus an actual CommandBus/QueryBus, and even Event Sourcing. 100% compatible with Kotlin, but there's a learning cost for the infrastructure (Axon Server or embedded mode).
3. **Implement it yourself** — write a lightweight CommandBus in Kotlin based on `Map<KClass<*>, CommandHandler<*>>`. An option if you don't want to add more framework dependencies.

This repository judged that option 1 is sufficient at the Account domain's use-case scale (6 commands, 2 queries), so it hasn't introduced Handler-based CQRS.

---

## Interface layer — the Controller calls the Service directly

```kotlin
// interfaces/rest/AccountController.kt — actual code
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
fun createAccount(
    @RequestHeader("X-User-Id") requesterId: String,
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult =
    createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
```

Since there's no Command/Query Bus, the Controller constructor-injects the Service and calls it directly. Error conversion is handled by the global `@RestControllerAdvice` (`common/GlobalExceptionHandler.kt`, → [error-handling.md](error-handling.md)), not by the Controller.

---

## The boundary with Domain Events

The events the Aggregate collects are pulled out and saved to the Outbox table by the Repository's `save()`, not by the Command Service — the Command Service only calls `outboxRelay.processPending()` right after saving. This implements the root's Outbox-based publishing as-is — see [domain-events.md](domain-events.md) for details.

---

### Related documents

- [layer-architecture.md](layer-architecture.md) — layer dependency direction, Command/Query Service roles
- [domain-events.md](domain-events.md) — the event-publishing mechanism, the Outbox pattern
- [repository-pattern.md](repository-pattern.md) — Repository method design
