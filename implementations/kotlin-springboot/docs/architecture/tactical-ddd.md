# Tactical Design — Kotlin Spring Boot

> For the framework-agnostic principles, see [root tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md).

## Aggregate Root — `private constructor()` + a `companion object` factory

```kotlin
// domain/Account.kt — actual code (partial). No jakarta.persistence import — plain Kotlin.
class Account private constructor() {

    var accountId: String = ""
        private set

    var balance: Money = Money(0, "")
        private set

    var status: AccountStatus = AccountStatus.ACTIVE
        private set

    private val domainEvents: MutableList<Any> = mutableListOf()

    companion object {
        fun create(ownerId: String, currency: String, email: String): Account =
            Account().apply {
                this.accountId = generateId()
                this.ownerId = ownerId
                this.email = email
                this.balance = Money(0, currency)
                this.status = AccountStatus.ACTIVE
                this.createdAt = LocalDateTime.now()
                this.updatedAt = this.createdAt
                this.domainEvents += AccountCreatedEvent(this.accountId, ownerId, email, currency, this.createdAt)
            }

        // Used when the Repository restores from persisted data — unlike create(), this never produces a domain event.
        fun reconstitute(accountId: String, /* ... */ status: AccountStatus, createdAt: LocalDateTime, updatedAt: LocalDateTime, deletedAt: LocalDateTime?): Account =
            Account().apply { /* reconstructs the given state as-is */ }
    }

    fun deposit(amount: Long): Transaction {
        if (status != AccountStatus.ACTIVE) throw DepositRequiresActiveAccountException()
        if (amount <= 0) throw InvalidAmountException()
        val money = Money(amount, balance.currency)
        balance = balance.add(money)
        // ...
    }

    fun pullDomainEvents(): List<Any> = domainEvents.toList().also { domainEvents.clear() }
}
```

**`private constructor()` + `companion object.create()`** is the Kotlin idiom for the root's "encapsulate creation logic inside the Aggregate." Since domain/ doesn't depend on JPA (persistence mapping is entirely handled by `infrastructure/persistence/AccountJpaEntity` + `AccountMapper`, see [directory-structure.md](directory-structure.md)), there's no need for a `protected` constructor for Hibernate's sake — the stronger `private` completely blocks external creation.

- `private constructor()`: external code (`Account()`) cannot create an empty instance. The only instance-creation path is the `companion object`'s factories (`create`/`reconstitute`).
- `companion object.create()`: the sole **public** creation path. Invariants (an account always starts with a 0 balance, a currency must be specified, etc) are enforced inside it, and `AccountCreatedEvent` is collected right at creation. `reconstitute()`, on the other hand, is used only when the Repository implementation restores DB state, and never produces an event.
- **Every property is `private set`**: direct external assignment like `account.status = AccountStatus.CLOSED` is impossible. A state change can only happen through a domain method like `deposit()`/`suspend()`/`close()` — the compiler enforces the root's "external code cannot directly change an Aggregate's internal state." The harness's `aggregate-no-public-setters` rule mechanically checks that every `var` property on a `class X private constructor()`-idiom class has `private set` attached, preventing regression where a public setter is accidentally added that bypasses this compiler enforcement.
- **`id: Long?` (the JPA surrogate key) doesn't exist in domain**: the DB-generated PK exists only on `AccountJpaEntity`; the domain identifier is `accountId: String`.

Each domain method immediately validating a business rule and throwing a concrete type from `sealed class AccountException` on violation is exactly the root principle too — see [error-handling.md](error-handling.md) for details.

---

## Entity — a child lifecycle object

```kotlin
// domain/Transaction.kt — actual code (partial). Plain Kotlin — no JPA import.
class Transaction private constructor() {

    var transactionId: String = ""
        private set

    companion object {
        fun create(accountId: String, type: TransactionType, amount: Money): Transaction =
            Transaction().apply {
                this.transactionId = generateId()
                this.accountId = accountId
                this.type = type
                this.amount = amount
                this.createdAt = LocalDateTime.now()
            }

        // Restoration only — called by TransactionMapper when reconstructing from the JPA entity (TransactionJpaEntity).
        fun reconstitute(transactionId: String, accountId: String, type: TransactionType, amount: Money, createdAt: LocalDateTime): Transaction =
            Transaction().apply { /* reconstructs the given state as-is */ }
    }
}
```

`Transaction` is an Entity whose equality is defined by its unique identifier, `transactionId`. Just like `Account`, it uses the `private constructor() + companion object.create()` factory pattern for consistency, and JPA mapping is entirely handled by `infrastructure/persistence/TransactionJpaEntity` + `TransactionMapper`. `Account.deposit()`/`withdraw()` calls `Transaction.create()` and temporarily holds it in `pendingTransactions`, and `AccountRepositoryImpl.save()` saves it together with `Account` (converted to `TransactionJpaEntity` via the Mapper) — the child Entity only being created/persisted through the Aggregate Root (`Account`) matches the root's "a child Entity is saved together via the Aggregate Root's Repository" principle.

---

## Value Object — `data class` + an `init` block (no manual `equals()` needed like in Java)

```kotlin
// domain/Money.kt — actual code. A plain data class — no JPA import.
// (@Embeddable column mapping is entirely handled by infrastructure/persistence/MoneyEmbeddable.)
data class Money(val amount: Long, val currency: String) {

    init {
        if (amount < 0) throw InvalidMoneyAmountException()
    }

    fun add(other: Money): Money {
        assertSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    fun isZero(): Boolean = amount == 0L

    private fun assertSameCurrency(other: Money) {
        if (currency != other.currency) throw CurrencyMismatchException()
    }
}
```

`data class` implements the root's "a Value Object is an immutable object whose equality is judged by the combination of its attributes" via `equals()`/`hashCode()`/`toString()` **automatically generated by the compiler** — what would need to be hand-written in TypeScript/Java as `equals(other: Money): boolean { return this.amount === other.amount && ... }` is replaced by a single `data class` keyword.

- **The `init` block**: validates invariants at constructor-call time. `Money(-100, "KRW")` raises an exception before the object is even created — an invalid Value Object state can never exist.
- **Only `val` properties are used**: since every field is immutable (`val`), `add()`/`subtract()` always return a new `Money` instance — they never mutate the existing instance.
- **`copy()`**: the `copy(amount = ..., currency = ...)` that `data class` provides automatically can create a new instance with only some fields changed — in this example, `add()`/`subtract()` construct a new `Money(...)` directly so `copy()` isn't used, but for a Value Object with many fields, `copy()` improves readability.

---

## Domain Event — a past-tense `data class`

```kotlin
// domain/AccountCreatedEvent.kt — actual code
data class AccountCreatedEvent(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val currency: String,
    val createdAt: LocalDateTime,
)
```

It follows the root principle exactly: a past-tense name (`AccountCreated`, `MoneyDeposited`) and an immutable data class. How to group event types under a `sealed interface` to get `when` exhaustiveness checking is covered in [domain-events.md](domain-events.md).

---

## Aggregate boundary — why Account and Transaction are grouped together

`Account` (the balance) and `Transaction` (the transaction history) are grouped into one Aggregate — `Transaction` is only saved/queried through `Account`'s Repository (`AccountRepository`). This boundary judgment matches the root's criteria.

- **An invariant that's created/changed together**: a deposit/withdrawal must always update the `balance` and record the `Transaction` together — if the balance changes without a transaction record, the audit trail breaks.
- **`Money` is a Value Object, not an Aggregate boundary**: it has no identifier and is reused in both `Account.balance`/`Transaction.amount` (embedded as `@Embeddable` via `MoneyEmbeddable` at the persistence layer) — there's no reason to split it into a separate Aggregate.
- **`notification/` is a Technical Service, not a separate Aggregate**: sending email is a technical side effect with no business invariant, so it isn't modeled as an Aggregate (→ [directory-structure.md](directory-structure.md)).

---

## Design principle summary — expression in Kotlin

| Root principle | Kotlin expression |
|---|---|
| Business rules are encapsulated in the Aggregate | a `private set` property + state changes only through domain methods |
| Creation logic is controlled | `private constructor()` + `companion object.create()` (no need for `protected` since there's no JPA dependency) |
| Value Object equality | `data class` (auto `equals`/`hashCode`/`copy`) |
| Invariants are validated immediately | an `init` block, `if (...) throw` inside a domain method |
| Typed errors | the `sealed class` exception hierarchy ([error-handling.md](error-handling.md)) |
| Domain Event | a past-tense `data class`, can be layered via `sealed interface` ([domain-events.md](domain-events.md)) |

### Related documents

- [layer-architecture.md](layer-architecture.md) — the Domain layer's role, the role of null-safety
- [repository-pattern.md](repository-pattern.md) — a Repository per Aggregate
- [domain-events.md](domain-events.md) — publishing/receiving Domain Events, the Outbox
- [error-handling.md](error-handling.md) — details of the sealed class exception hierarchy
- harness `no-cross-aggregate-reference` rule (`../../harness/README.md`) — mechanically fails if, within the same BC (`payment/domain/`), Payment directly references Refund (or vice versa) as a field
- harness `no-cross-bc-domain-import` rule (`../../harness/README.md`) — checks that the principle "another Aggregate may only be referenced by ID (object references are forbidden)" also applies between different BCs — fails if a `<bc>/domain/` file directly imports another BC's `domain/` package
