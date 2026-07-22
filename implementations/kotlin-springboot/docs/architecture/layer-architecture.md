# Layer Architecture — Kotlin Spring Boot

> For the framework-agnostic principles, see [root layer-architecture.md](../../../../docs/architecture/layer-architecture.md).

## Dependency direction

```
interfaces/rest (@RestController)  →  application/{command,query} (@Service)  →  domain (Account, AccountRepository interface)
                                                                                        ↑
                                                                        infrastructure/persistence (@Repository, AccountRepositoryImpl)
```

`account/domain/` is written as plain Kotlin with no Spring annotations and no JPA (`jakarta.persistence`) annotations either (JPA mapping is entirely handled by `AccountJpaEntity`/`MoneyEmbeddable` + the Mapper in `infrastructure/persistence/`, see [directory-structure.md](directory-structure.md)), and `infrastructure/persistence/AccountRepositoryImpl` implements the `domain/AccountRepository` interface to invert the dependency. The harness's `domain-purity` (forbids `@Service`/`@Component`/`@Repository`/`@Controller` and `jakarta.persistence` imports in domain), `service-annotation` (`@Service` only inside application/), and `repository-annotation` (`@Repository` only inside infrastructure/) checks actually enforce this dependency direction. The `domain-layer-isolation` rule enforces the same principle once more, via a broader path-based check than the annotation blocklist — it blocks a `domain/` file from importing `application/`·`infrastructure/`·`interfaces/` packages at all (whether from its own domain or a sibling domain). The `interface-no-infrastructure` rule blocks the opposite end (`interfaces/`) from skipping past `application/` and importing `infrastructure/` directly — `interfaces/` must depend only on `application/`.

---

## The Domain layer

```kotlin
// domain/AccountRepository.kt — actual code. a plain interface, no Spring dependency
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

The root expresses the Repository interface as a TypeScript `abstract class` (because NestJS DI can't use an interface as a runtime token), but **in Kotlin/Spring, the `interface` itself is the DI token** — Spring finds the sole `@Repository` Bean implementing `AccountRepository` on the classpath (`AccountRepositoryImpl`) and auto-binds it. No separate `abstract class` workaround is needed.

Kotlin's **null-safety** plays a different role in this layer than the root's TypeScript version: rather than having a dedicated single-item lookup method, "not found" is engraved into the type system by calling `firstOrNull()` on the `List<Account>` inside the `Pair` that `findAccounts(...)` returns (see repository-pattern.md). The caller can't compile without either the `?:` Elvis operator or a smart cast, so forgetting a null check is simply not possible.

```kotlin
// application/command/DepositService.kt — actual code
val (accounts, _) = accountRepository.findAccounts(
    AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
)
val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
// After this line, account is smart-cast to the Account (non-null) type — the compiler guarantees
// non-null even when accessing account.email etc directly, with no Optional.get() or null check
```

What would require `Optional<Account>` (Java) or `Account | undefined` (TS) + explicit unwrapping in Java/TypeScript ends in a single `?:` line and a smart cast in Kotlin — **null-safety works here as a tool that "blocks code violating a domain invariant at the compile stage."** The domain rule "an account must exist in any code that runs after the account is found" is enforced through the type system.

---

## The Application layer — Command/Query Service

```kotlin
// application/command/CreateAccountService.kt — actual code
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)      // @Transactional — saving the Account + writing the Outbox, one transaction
        return CreateAccountResult(/* ... */)
        // Ends here — draining the Outbox (OutboxPoller/OutboxConsumer) is handled independently
        // by a separate component. See domain-events.md
    }
}
```

- **Constructor injection — no `@Autowired` needed**: the primary constructor's parameters are the DI target as-is. Spring 4.3+ lets you omit even the `@Autowired` annotation as long as there's only one constructor.
- **Why `open` is needed**: a Kotlin class is `final` by default. Spring AOP (the `@Transactional` proxy) needs to create a class-inheritance-based proxy (CGLIB), so `@Service`/`@Repository` classes need to be `open`. This repository applies `kotlin("plugin.spring")` in `build.gradle.kts`, which makes the compiler automatically treat any class annotated with a `@Component`-family annotation as `open` — there's no need to write the `open` keyword in the source yourself.
- **Business logic is delegated to the Aggregate**: `CreateAccountService` just calls `Account.create()` — it never implements balance calculation or state-transition rules directly.

A Query Service is distinguished by `@Transactional(readOnly = true)` — Hibernate skips dirty checking and flushing, optimizing read performance.

```kotlin
// application/query/GetAccountService.kt — actual code
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) { /* ... */ }
```

A Query Service injects the read-only `AccountQuery`, not the write model `AccountRepository` — see [cqrs-pattern.md](cqrs-pattern.md) for details.

See [cqrs-pattern.md](cqrs-pattern.md) for details on Command/Query granularity.

---

## The Infrastructure layer

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code (partial)
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val em: EntityManager,
) : AccountRepository, AccountQuery {

    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> { /* ... */ }

    override fun saveAccount(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
    }

    // AccountQuery (the read-only port) declares findAccounts/findTransactions with exactly the same
    // signature as AccountRepository, so the findAccounts override above satisfies both interfaces at once.
}
```

`AccountRepositoryImpl` implements `AccountRepository`, and Spring automatically performs the `interface → implementation` binding (no `@Qualifier` needed since there's only one implementation on the classpath). Using both `EntityManager` (assembling dynamic JPQL queries) and `AccountJpaRepository` (Spring Data's basic CRUD) is also only permitted in this layer — the Domain/Application layers don't know the JPA API exists at all.

Transaction propagation is replaced with Spring's declarative `@Transactional`, instead of the root's manual `AsyncLocalStorage`/`ThreadLocal` pattern — see [persistence.md](persistence.md) for details.

---

## The Interfaces layer

```kotlin
// interfaces/rest/AccountController.kt — actual code (partial)
@RestController
@RequestMapping("/accounts")
class AccountController(
    private val createAccountService: CreateAccountService,
    private val getAccountService: GetAccountService,
    // ...
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestHeader("X-User-Id") requesterId: String,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult =
        createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
}
```

The root's principle that "the Interface DTO is a thin wrapper over the Application object" (TypeScript `extends`) is expressed in Kotlin as **a `data class` constructor call** — instead of wrapping via inheritance, the Request DTO's fields are mapped directly into the Command's constructor arguments. `CreateAccountRequest` (interfaces/rest) and `CreateAccountCommand` (application/command) are separate `data class`es, and a single line in the Controller method is the mapping point — thanks to Kotlin's named arguments, mapping stays free of ordering mistakes even as fields grow.

Error conversion (`@ExceptionHandler`) being this layer's responsibility is the same as the root — see [error-handling.md](error-handling.md) for details.

---

## Principle summary

| Principle | Expression in Kotlin/Spring |
|---|---|
| Only upper layers depend on lower layers | enforced via package structure + harness checks (`domain-purity`, etc) |
| Domain has no framework dependency | Spring annotations forbidden (JPA is an exception, see [directory-structure.md](directory-structure.md)) |
| Repository is split into interface/implementation | `interface` (domain) + an implementation (infrastructure) that Spring auto-binds |
| Expressing "not found" | `T?` + `?:` instead of `Optional<T>` — the compiler blocks a missed null check |
| DI | constructor injection, no `@Autowired` needed |

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object details
- [repository-pattern.md](repository-pattern.md) — Repository pattern details
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Service separation
- [domain-events.md](domain-events.md) — Domain Event, the Outbox pattern
