# API Response Structure (Spring Boot)

> For the framework-agnostic principles, see the root [api-response.md](../../../../docs/architecture/api-response.md).

## Pagination — current implementation

`getTransactions` in `account/interfaces/rest/AccountController.java`:

```java
@GetMapping("/{accountId}/transactions")
public GetTransactionsResult getTransactions(
        @RequestHeader("X-User-Id") String requesterId,
        @PathVariable String accountId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int take
) {
    return getTransactionsService.getTransactions(accountId, requesterId, page, take);
}
```

Receiving the `page` (0-based)/`take` query parameters via `@RequestParam(defaultValue = ...)` matches the root principle exactly. Spring MVC converts them to `int` automatically, so no separate parsing code is needed.

```
GET /accounts/{accountId}/transactions?page=0&take=20
```

---

## List query response — the Result record

`account/application/query/GetTransactionsResult.java` defines the response schema (the actual record definition is similar to the structure below):

```java
public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(
            String transactionId, String type, MoneyResult amount, LocalDateTime createdAt) {}
    public record MoneyResult(long amount, String currency) {}
}
```

JSON serialization result:

```json
{
  "transactions": [
    { "transactionId": "...", "type": "DEPOSIT", "amount": { "amount": 10000, "currency": "KRW" }, "createdAt": "..." }
  ],
  "count": 2
}
```

- Both the fact that the key name is the plural of the domain object name (`transactions`) and the fact that a generic key like `result`/`data` is not used follow the root principle.
- `count` is `TransactionsWithCount.count()` (the total record count) returned by `AccountRepository.findTransactions(accountId, page, take)`, not the size of the current page.

---

## Single-record response — no wrapper

The Controller returns `GetAccountResult`, returned by `GetAccountService.getAccount()`, as-is (it is not wrapped in a generic envelope like `{ success: true, data: {...} }`):

```java
@GetMapping("/{accountId}")
public GetAccountResult getAccount(@RequestHeader("X-User-Id") String requesterId, @PathVariable String accountId) {
    return getAccountService.getAccount(accountId, requesterId);
}
```

Spring auto-serializes the `GetAccountResult` record via Jackson, producing JSON that returns the domain object directly. Since distinguishing error/success responses is handled by the HTTP status code (mapped by `@ExceptionHandler`), no wrapper is needed.

---

## Repository query method — unified into a single `find<Noun>s`

Root principle: list-query methods must return **an array of domain objects + a count**, and single-record lookups must be unified under the `find<Noun>s(take: 1)` pattern (a separate `findOne` is forbidden).

`AccountRepository` follows this principle exactly:

```java
public interface AccountRepository {
    AccountsWithCount findAccounts(AccountFindQuery query);
    void saveAccount(Account account);
    ...
}

public record AccountsWithCount(List<Account> accounts, long count) {}
```

In line with the root's rule of "only one `find<Noun>s` for reads," single-record lookups are unified by calling `findAccounts(query)` with `accountId`+`ownerId`+`take:1` filters and then extracting the first result:

```java
// Calling side — a pattern that repeats in application/command/DepositService.java, etc.
Account account = accountRepository
        .findAccounts(new AccountFindQuery(0, 1, accountId, ownerId, null))
        .accounts().stream().findFirst()
        .orElseThrow(() -> new AccountException(...));
```

For the same reason, `Transaction` (child entity) lookups are merged from two separate methods, `findTransactions`/`countTransactions`, into a single `findTransactions` that returns `TransactionsWithCount` (list + count). See [repository-pattern.md](repository-pattern.md) for details.

---

## Dynamic filter-condition pattern

`AccountRepositoryImpl.buildJpql()` implements the pattern of adding a condition only when a value is present:

```java
if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
```

`AccountFindQuery` (`account/domain/AccountFindQuery.java`) is the input record for this dynamic query:

```java
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

---

## Harness verification

`harness/src/rules/NoGenericResponseKeys.java` (rule: `no-generic-response-keys`) checks that, for records following the `*Result`/`*Response`/`*WithCount` naming convention, top-level component fields of type `List<...>` do not use a generic key such as `result`/`data`/`items` — a regression guard for the "list query response format" principle above. Sub-collections nested inside a single-record response (a domain concept like an order's own line items) are not top-level records, so they are not checked.

`harness/src/rules/QueryHandlerNoRawAggregate.java` (rule: `query-handler-no-raw-aggregate`) checks that the return types of `public` methods defined by Query Services in `application/query/` and REST Controllers in `interfaces/` never directly expose (or wrap in a generic such as `List<...>`) a raw Aggregate/Entity class from `domain/` (`Account`/`Transaction`/`Card`/`Payment`/`Refund`/`Credential`, etc. — collected dynamically from `public class` declarations in `domain/`, not hardcoded) — a regression guard for the principle that Query Services/Controllers must always return a dedicated Result/DTO type.

### Related documents

- [repository-pattern.md](repository-pattern.md) — Repository method design, single-record lookup pattern
- [layer-architecture.md](layer-architecture.md) — Query Service, Result objects
- [cqrs-pattern.md](cqrs-pattern.md) — why the Query Service returns a Result
