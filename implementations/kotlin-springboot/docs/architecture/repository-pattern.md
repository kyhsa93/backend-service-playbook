# Repository Pattern — Kotlin Spring Boot

> For the framework-agnostic principles, see [root repository-pattern.md](../../../../docs/architecture/repository-pattern.md).

## Current implementation — the interface/implementation split is correct

```
account/
  domain/
    AccountRepository.kt          ← interface (no Spring dependency)
  infrastructure/
    persistence/
      AccountJpaRepository.kt     ← extends Spring Data's JpaRepository
      AccountRepositoryImpl.kt    ← @Repository, the AccountRepository implementation
```

1 Aggregate (`Account`) = 1 Repository interface + 1 implementation, with the interface in `domain/` and the implementation in `infrastructure/persistence/` — this matches the root principle exactly. The harness's `repository-annotation` check (whether `@Repository` is inside `infrastructure/`) actually enforces this.

For DI binding, instead of the root's TypeScript `abstract class` token, **the Kotlin `interface` itself** becomes the token — Spring finds `AccountRepository`'s sole implementation (the `@Repository` Bean) on the classpath and auto-injects it.

```kotlin
// an Application Service — injected as the interface type (actual code)
class CreateAccountService(private val accountRepository: AccountRepository)
```

---

## Query/save method naming — unified into `find<Noun>s`/`save<Noun>` (enforced by the harness's `repository-naming` rule)

```kotlin
// domain/AccountRepository.kt — actual code
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // returns the list + count together
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}

data class AccountFindQuery(
    val page: Int,
    val take: Int,
    val accountId: String? = null,
    val ownerId: String? = null,
    val status: List<String>? = null,
)

data class TransactionFindQuery(
    val accountId: String,
    val page: Int,
    val take: Int,
)
```

All three things the root convention requires are reflected in the actual code.

| Purpose | Root pattern | Current code |
|------|--------------|------|
| List query | only a single `find<Noun>s`, single-item lookups also reuse it via `take: 1` | unified into a single `findAccounts` — the old `findByAccountIdAndOwnerId` (single-item-only) + `findAll`/`countAll` (separate list/count) three methods are gone |
| Save | `save<Noun>` | `saveAccount` — includes the noun suffix |
| Delete | `delete<Noun>` | `deleteAccount(accountId)` — already matched before |

Having a dedicated single-item lookup method like the old `findByAccountIdAndOwnerId` was exactly the "dedicated findOne method" pattern the root explicitly forbids — it also had a scalability problem where the method count kept growing every time a query condition was added (`findByAccountIdAndStatus`, etc). `Pair<List<Account>, Long>` corresponds to the root's `{ orders: Order[], count: number }` object return — in Kotlin you could also declare a dedicated response `data class` (e.g. `AccountsWithCount(accounts: List<Account>, count: Long)`), and if there are only a few call sites, a `Pair` is sufficient. Since there are only 2 fields, `Pair` isn't overkill, but if there's room for more fields later, a named `data class` reads better.

The same principle is applied to querying `Transaction` (a child entity of the Account Aggregate) too — the two methods `findTransactions`/`countTransactions` have been merged into a single `findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>`.

```kotlin
// application/command/DepositService.kt — a single-item lookup reused via take=1 (actual code)
@Service
class DepositService(
    private val accountRepository: AccountRepository,
) {
    fun deposit(command: DepositCommand): TransactionResult {
        val (accounts, _) = accountRepository.findAccounts(
            AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
        )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.deposit(command.amount)
        accountRepository.saveAccount(account)   // @Transactional — saving the Account + writing the Outbox, one transaction
        /* ... */
        // Ends here — draining the Outbox (OutboxPoller/OutboxConsumer) is handled by a separate component.
    }
}
```

`.firstOrNull()` is the Kotlin idiom corresponding to the root's `.pop()` / `.then(r => r.orders.pop())` pattern — if the list is empty it's `null`, and `?:` throws the exception right away. It ends in one line, with none of Java's `Optional<T>` wrapping or index access (`[0]`, with the risk of `IndexOutOfBoundsException`). All 6 Command Services that query then modify an account (`Deposit`/`Withdraw`/`Suspend`/`Close`/`Reactivate`/`Delete`) use this pattern. `CreateAccountService` creates a new Aggregate, so it just calls `saveAccount(account)` without a query.

**The CQRS read-only port (`AccountQuery`) reuses the same signature too.** `GetAccountService`/`GetTransactionsService` depend on `application/query/AccountQuery`, not `AccountRepository` (the write model) — this compile-time-enforces that a Query Service can never access a write method like `saveAccount`/`deleteAccount` (the harness's `cqrs-pattern` rule actually checks this). `AccountQuery` is a separate interface, but declares `findAccounts(query: AccountFindQuery)`/`findTransactions(query: TransactionFindQuery)` with exactly the same signature as `AccountRepository` — when `AccountRepositoryImpl` implements both interfaces, a single override satisfies both at once. Card/Payment/Refund's `*Query` interfaces (`CardQuery.findCards`/`PaymentQuery.findPayments`/`RefundQuery.findRefunds`) all follow the same pattern — see [cqrs-pattern.md](cqrs-pattern.md) for details.

---

## The implementation

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository, AccountQuery {

    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> {
        val accounts = /* reuses the old findAll logic */ /* ... */
        val count = /* reuses the old countAll logic */ /* ... */
        return accounts to count
    }

    @Transactional
    override fun saveAccount(account: Account) {
        jpaRepository.save(/* ... */)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(/* ... */)
        outboxWriter.saveAll(account.pullDomainEvents())
    }

    @Transactional
    override fun deleteAccount(accountId: String) {
        val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
        account.markDeleted()   // Account.markDeleted() — throws DeleteRequiresClosedAccountException unless the state is CLOSED
        jpaRepository.save(account)
    }

    override fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long> {
        val transactions = transactionJpaRepository
            .findByAccountIdOrderByCreatedAtDesc(query.accountId, PageRequest.of(query.page, query.take))
            .map(TransactionMapper::toDomain)
        val count = transactionJpaRepository.countByAccountId(query.accountId)
        return transactions to count
    }

    // AccountQuery (the read-only port) declares findAccounts/findTransactions with exactly the same
    // signature as AccountRepository, so the two overrides above satisfy both interfaces at once — no
    // separate overload is needed.
}
```

**The Repository never has a separate update method** — `AccountRepositoryImpl` has no `updateAccount()`-style method; every state change happens through a domain method (`Account.deposit()`/`suspend()`/`close()`/`markDeleted()`, etc), then is persisted via `saveAccount()`. The harness's `repository-naming` rule also catches a method starting with `update` on its blocklist, enforcing this principle.

**`close()` (a state transition) and `markDeleted()` (soft delete) are different lifecycle events.** `Account.close()` only changes the state to `AccountStatus.CLOSED` and never touches `deletedAt` — a `CLOSED` account must still be queryable via `GetAccountService` (checking account history, etc). `deleteAccount()`, on the other hand, sets `deletedAt`, excluding it from every subsequent query (the `deletedAt IS NULL` condition). `Account.markDeleted()` throws `DeleteRequiresClosedAccountException` if `status != CLOSED`, blocking an active account from being deleted directly without going through the closing procedure — deletion always goes through the two steps "close → delete."

---

## Dynamic filters

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — actual code
private fun buildJpql(query: AccountFindQuery, count: Boolean): String {
    val select = if (count) "SELECT COUNT(a)" else "SELECT a"
    val sb = StringBuilder("$select FROM Account a WHERE a.deletedAt IS NULL")
    if (!query.accountId.isNullOrBlank()) sb.append(" AND a.accountId = :accountId")
    if (!query.ownerId.isNullOrBlank()) sb.append(" AND a.ownerId = :ownerId")
    if (!query.status.isNullOrEmpty()) sb.append(" AND a.status IN :status")
    if (!count) sb.append(" ORDER BY a.accountId DESC")
    return sb.toString()
}
```

Adding a condition only when a value is present (`if (!query.accountId.isNullOrBlank())`) matches the root's dynamic-filter principle exactly. Having the `deletedAt IS NULL` condition applied by default on every query also matches the soft-delete query principle.

---

## Principle summary

- **1 Aggregate = 1 Repository interface + implementation**.
- **`delete<Noun>` has been added**: `deleteAccount(accountId)` exists in the actual code, and enforces via `Account.markDeleted()` that only a CLOSED account can be deleted.
- **Unified into `find<Noun>s`/`save<Noun>`**: `findByAccountIdAndOwnerId`/`findAll`/`countAll`/`save` → renamed to `findAccounts`/`saveAccount`. `findTransactions`/`countTransactions` have also been merged into a single `findTransactions(query): Pair<...>`. This unification is applied not only to `AccountRepository` (the write model) but also to `AccountQuery` (the read-only port) as-is, and both the Repository/Query of Card (`findCards`/`saveCard`)·Payment (`findPayments`/`savePayment`)·Refund (`findRefunds`/`saveRefund`)·Auth (`CredentialQuery.findCredentials`) follow the same shape. This rule is automatically enforced by the harness's `repository-naming` check (it scans `*Repository`/`*Query` interface methods inside `domain/`, `application/query/`, catching `findBy...`/a bare `findAll`/`count*`/`save`/`delete` on a blocklist) — even if a doc says "done," if an interface's renaming was actually missed (as `CredentialQuery.findByUserId` once was), it surfaces as a harness FAIL.
- **A single-item lookup uses `take: 1` + `firstOrNull()`**: no dedicated findOne method is created — all 6 Command Services use this pattern.
- **No update method on the Repository**: state changes happen via an Aggregate domain method + `saveAccount`/`deleteAccount`.
- **Dynamic filters, soft-delete query conditions**: a condition is only added when a value is present, and `deletedAt IS NULL` is applied by default to every query.

### Related documents

- [persistence.md](persistence.md) — transaction propagation, Soft Delete wiring, migrations
- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root design
- [api-response.md](api-response.md) — the relationship between the Repository return format and the API response
- [domain-events.md](domain-events.md) — saving to the Outbox from the Repository
- [cqrs-pattern.md](cqrs-pattern.md) — the relationship with the read-only `AccountQuery` port
