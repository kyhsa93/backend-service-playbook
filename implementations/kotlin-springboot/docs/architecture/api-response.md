# API 응답 구조 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root api-response.md](../../../../docs/architecture/api-response.md) 참조.

## 페이지네이션

`page`(0부터 시작)/`take` 쿼리 파라미터를 컨트롤러 메서드 파라미터의 기본값으로 표현한다.

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

Kotlin/Spring MVC에서는 `@RequestParam(defaultValue = "...")`가 값을 문자열로 받아 타입 변환하므로, root의 `page=0`, `take=20` 기본값을 그대로 문자열 리터럴로 옮기면 된다. `Int?` nullable로 받고 `?: 0`을 쓰는 것보다 `defaultValue`가 더 간결하고 컨트롤러 시그니처에서 기본값이 바로 드러난다.

---

## 목록 조회 응답 형식

`examples/.../application/query/GetTransactionsResult.kt`가 이미 root 원칙을 정확히 따른다:

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

- 키 이름은 도메인 객체 복수형(`transactions`) — `result`/`data`/`items` 같은 범용 키를 쓰지 않는다
- `count`는 필터 적용 후 전체 건수
- Kotlin의 **nested `data class`**(`TransactionSummary`, `MoneyResult`)로 응답 스키마를 하나의 파일에서 계층적으로 표현할 수 있다 — Java였다면 별도 파일 또는 정적 내부 클래스 + Lombok이 필요했을 코드가 한 줄씩으로 끝난다.

---

## 단건 조회 응답 형식

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

`AccountController.getAccount()`는 이 `data class`를 그대로 `@RestController`가 Jackson으로 직렬화하도록 반환한다. `{ success: true, data: {...} }` 같은 범용 래퍼로 감싸지 않는다 — 에러/정상 구분은 HTTP 상태 코드가 담당한다(`@ResponseStatus`, `ResponseEntity`).

---

## Repository 조회 메서드 반환 형식 — 통일 완료

root 원칙: 목록 조회 메서드는 항상 `{ <noun>s: List<T>, count: Long }` 형태를 반환해야 하고, 단건 조회 전용 메서드(`findOne`)를 별도로 두지 않는다.

`AccountRepository`(쓰기 모델)가 이 원칙을 그대로 따른다.

```kotlin
// domain/AccountRepository.kt — 실제 코드
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // 목록/단건(take=1)/count를 모두 처리
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

상세는 [repository-pattern.md](repository-pattern.md)에서 다룬다 — 요지는 `findAccounts(query): Pair<List<Account>, Long>` 하나로 통일하고, `CloseAccountService` 등에서 `take = 1`로 호출한 뒤 `.firstOrNull()`로 꺼내는 것이다.

---

## Result 객체 설계

Query Service는 Aggregate(`Account`)를 직접 반환하지 않고 전용 `data class` Result를 반환한다. `GetAccountService.getAccount()`가 이 원칙을 그대로 따른다:

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

**Aggregate를 직접 노출하지 않는 이유**: `Account`는 JPA `@Entity`이며 `pullDomainEvents()`, `pullPendingTransactions()` 같은 내부 상태 접근 메서드를 가진다. Jackson이 이를 그대로 직렬화하면 내부 구현이 노출되고, 지연 로딩(lazy loading) 프록시 직렬화 문제(`LazyInitializationException`)로도 이어질 수 있다. Result `data class`는 응답에 필요한 필드만 노출하는 별도의 계약이다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계
- [layer-architecture.md](layer-architecture.md) — Query Service, Result 객체
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query 분리
