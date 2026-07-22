# API Response Structure — Kotlin Spring Boot

> For the framework-agnostic principles, see [root api-response.md](../../../../docs/architecture/api-response.md).

## Pagination

The `page` (0-based)/`take` query parameters are expressed as default values on the controller method parameters.

```kotlin
// interfaces/rest/AccountController.kt
@GetMapping("/{accountId}/transactions")
fun getTransactions(
    @RequestHeader("X-User-Id") requesterId: String,
    @PathVariable accountId: String,
    @RequestParam(defaultValue = "0") page: Int,
    @RequestParam(defaultValue = "20") take: Int,
): GetTransactionsResult = getTransactionsService.getTransactions(accountId, requesterId, page, take)
```

In Kotlin/Spring MVC, `@RequestParam(defaultValue = "...")` receives the value as a string and type-converts it, so the root's `page=0`, `take=20` defaults can be carried over directly as string literals. `defaultValue` is more concise than receiving it as `Int?` nullable and using `?: 0`, and it makes the default immediately visible in the controller signature.

---

## List query response format

`examples/.../application/query/GetTransactionsResult.kt` already follows the root principle exactly:

```kotlin
data class GetTransactionsResult(val transactions: List<TransactionSummary>, val count: Long) {
    data class TransactionSummary(
        val transactionId: String,
        val type: String,
        val amount: MoneyResult,
        val createdAt: LocalDateTime,
    )
    data class MoneyResult(val amount: Long, val currency: String)
}
```

- The key name is the plural of the domain object (`transactions`) — never a generic key like `result`/`data`/`items`
- `count` is the total count after filters are applied
- Kotlin's **nested `data class`** (`TransactionSummary`, `MoneyResult`) lets the response schema be expressed hierarchically in a single file — code that in Java would need a separate file or a static inner class plus Lombok collapses to a single line each.

---

## Single-item query response format

```kotlin
// application/query/GetAccountResult.kt
data class GetAccountResult(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val balance: MoneyResult,
    val status: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
) {
    data class MoneyResult(val amount: Long, val currency: String)
}
```

`AccountController.getAccount()` returns this `data class` as-is for the `@RestController` to serialize with Jackson. It is never wrapped in a generic wrapper like `{ success: true, data: {...} }` — distinguishing error/success is the job of the HTTP status code (`@ResponseStatus`, `ResponseEntity`).

---

## Repository query method return format — unified

Root principle: list-query methods must always return the shape `{ <noun>s: List<T>, count: Long }`, and a dedicated single-item query method (`findOne`) is never introduced separately.

`AccountRepository` (the write model) follows this principle exactly.

```kotlin
// domain/AccountRepository.kt — actual code
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // handles list/single-item(take=1)/count all together
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

Details are covered in [repository-pattern.md](repository-pattern.md) — the gist is unifying everything into a single `findAccounts(query): Pair<List<Account>, Long>`, then calling it with `take = 1` in places like `CloseAccountService` and pulling the item out with `.firstOrNull()`.

---

## Result object design

The Query Service never returns the Aggregate (`Account`) directly — it returns a dedicated Result `data class`. `GetAccountService.getAccount()` follows this principle exactly:

```kotlin
fun getAccount(accountId: String, requesterId: String): GetAccountResult {
    val (accounts, _) = accountQuery.findAccounts(AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId))
    val account = accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)
    return GetAccountResult(
        accountId = account.accountId,
        ownerId = account.ownerId,
        email = account.email,
        balance = GetAccountResult.MoneyResult(account.balance.amount, account.balance.currency),
        status = account.status.name,
        createdAt = account.createdAt,
        updatedAt = account.updatedAt,
    )
}
```

**Why the Aggregate is never exposed directly**: `Account` is a JPA `@Entity` and has internal-state-accessing methods like `pullDomainEvents()`, `pullPendingTransactions()`. If Jackson serialized it as-is, internal implementation details would leak, and it could also lead to lazy-loading proxy serialization issues (`LazyInitializationException`). The Result `data class` is a separate contract that exposes only the fields the response needs.

---

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository method design
- [layer-architecture.md](layer-architecture.md) — Query Service, Result objects
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query separation
- harness `no-generic-response-keys` rule (`../../harness/README.md`) — mechanically fails if a list-response `data class`'s `List<...>` property uses a generic key like `result`/`data`/`items`
- harness `query-handler-no-raw-aggregate` rule (`../../harness/README.md`) — mechanically fails if a Query Service/Controller exposes a raw Domain Aggregate directly as its return type
