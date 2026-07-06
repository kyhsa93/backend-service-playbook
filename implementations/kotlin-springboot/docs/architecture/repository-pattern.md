# Repository 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root repository-pattern.md](../../../../docs/architecture/repository-pattern.md) 참조.

## 현재 구현 — 인터페이스/구현 분리는 올바르다

```
account/
  domain/
    AccountRepository.kt          ← interface (Spring 무의존)
  infrastructure/
    persistence/
      AccountJpaRepository.kt     ← Spring Data JpaRepository 확장
      AccountRepositoryImpl.kt    ← @Repository, AccountRepository 구현체
```

1 Aggregate(`Account`) = 1 Repository 인터페이스 + 1 구현체, `domain/`에 인터페이스·`infrastructure/persistence/`에 구현체 배치는 root 원칙과 정확히 일치한다. harness의 `repository-annotation` 검사(`@Repository`가 `infrastructure/` 안에 있는지)가 이를 실제로 강제한다.

DI 바인딩은 root의 TypeScript `abstract class` 토큰 대신 **Kotlin `interface` 자체**가 토큰이 된다 — Spring이 클래스패스에서 `AccountRepository`의 유일한 구현체(`@Repository` Bean)를 찾아 자동 주입한다.

```kotlin
// Application Service — interface 타입으로 주입받음 (실제 코드)
class CreateAccountService(private val accountRepository: AccountRepository)
```

---

## 알려진 갭 — 메서드 네이밍이 root 컨벤션과 다르다

```kotlin
// domain/AccountRepository.kt — 실제 코드
interface AccountRepository {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?   // ← 단건 전용 메서드
    fun findAll(query: AccountFindQuery): List<Account>                          // ← count와 분리
    fun countAll(query: AccountFindQuery): Long
    fun save(account: Account)
    fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction>
    fun countTransactions(accountId: String): Long
}
```

root 컨벤션은 다음 세 가지를 요구한다.

| 목적 | root 패턴 | 현재 코드 |
|------|--------------|------|
| 목록 조회 | `find<Noun>s` 하나만, 단건도 `take: 1`로 재사용 | `findByAccountIdAndOwnerId`(단건 전용) + `findAll`(목록) 두 갈래로 분산 |
| 저장 | `save<Noun>` | `save` — noun 접미사 없음(단일 Aggregate뿐이라 모호하지 않지만 컨벤션 불일치) |
| 삭제 | `delete<Noun>` | 없음 — [persistence.md](persistence.md)에서 다룬 Soft Delete 배선 갭과 동일한 원인 |

`findByAccountIdAndOwnerId`처럼 전용 단건 조회 메서드를 두는 것은 root가 명시적으로 금지하는 "findOne 전용 메서드" 패턴이다 — 조회 조건이 하나 늘어날 때마다(`findByAccountIdAndStatus` 등) 메서드가 계속 늘어나는 확장성 문제도 있다.

---

## 올바른 형태 — `findAccounts` 하나로 통일

```kotlin
// domain/AccountRepository.kt — 제안
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // 목록 + count를 함께 반환
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)                                   // soft delete — persistence.md 참조

    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}

data class AccountFindQuery(
    val page: Int,
    val take: Int,
    val accountId: String? = null,
    val ownerId: String? = null,
    val status: List<AccountStatus>? = null,
)
```

`Pair<List<Account>, Long>`이 root의 `{ orders: Order[], count: number }` 객체 반환에 대응한다 — Kotlin에서는 전용 응답 `data class`(예: `AccountsWithCount(accounts: List<Account>, count: Long)`)를 별도로 선언해도 되고, 호출부가 하나뿐이라면 `Pair`로 충분하다. 필드가 2개뿐이므로 `Pair`가 과하지 않지만, 이후 필드가 늘어날 여지가 있다면 named `data class`가 가독성이 낫다.

```kotlin
// application/query/GetAccountService.kt — 단건 조회는 take=1로 재사용 (제안)
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountRepository: AccountRepository) {

    fun getAccount(accountId: String, requesterId: String): GetAccountResult {
        val (accounts, _) = accountRepository.findAccounts(
            AccountFindQuery(page = 0, take = 1, accountId = accountId, ownerId = requesterId),
        )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(accountId)
        return GetAccountResult(/* ... */)
    }
}
```

`.firstOrNull()`이 root의 `.pop()` / `.then(r => r.orders.pop())` 패턴에 대응하는 Kotlin 관용구다 — 리스트가 비어 있으면 `null`, `?:`로 즉시 예외를 던진다. Java의 `Optional<T>` 래핑이나 인덱스 접근(`[0]`, `IndexOutOfBoundsException` 위험) 없이 한 줄로 끝난다.

---

## 구현체 — `save<Noun>`/`delete<Noun>`로 리네이밍

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 제안 (메서드명만 변경)
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val em: EntityManager,
) : AccountRepository {

    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> {
        val accounts = /* 기존 findAll 로직 재사용 */ emptyList<Account>()
        val count = /* 기존 countAll 로직 재사용 */ 0L
        return accounts to count
    }

    override fun saveAccount(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
    }

    override fun deleteAccount(accountId: String) {
        val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
        account.markDeleted()
        jpaRepository.save(account)
    }
}
```

**Repository에 update 메서드를 별도로 두지 않는다** — 이 원칙은 이미 지켜지고 있다. `AccountRepositoryImpl`에 `updateAccount()` 류의 메서드가 없고, 모든 상태 변경은 `Account.deposit()`/`suspend()`/`close()` 등 도메인 메서드로 이루어진 뒤 `saveAccount()`로 영속화된다.

---

## 동적 필터 — 이미 올바르게 구현됨

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드
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

값이 있을 때만 조건을 추가하는 방식(`if (!query.accountId.isNullOrBlank())`)이 root의 동적 필터 원칙과 정확히 일치한다. `deletedAt IS NULL` 조건이 모든 조회에 기본 적용되는 것도 soft delete 조회 원칙에 맞다 — 삭제 실행 경로만 빠져 있을 뿐(→ [persistence.md](persistence.md)), 조회 측 필터링은 처음부터 올바르게 구현되어 있었다.

---

## 원칙 요약

- **1 Aggregate = 1 Repository 인터페이스 + 구현체**: 이미 올바름.
- **`find<Noun>s`/`save<Noun>`/`delete<Noun>` 세 메서드로 통일**: 현재 `findByAccountIdAndOwnerId`/`findAll`/`save`(delete 없음) → `findAccounts`/`saveAccount`/`deleteAccount`로 리네이밍 필요.
- **단건 조회는 `take: 1` + `firstOrNull()`**: 전용 findOne 메서드를 만들지 않는다.
- **Repository에 update 메서드 없음**: 이미 올바름 — 상태 변경은 Aggregate 도메인 메서드 + `save<Noun>`.
- **동적 필터, soft-delete 조회 조건**: 이미 올바름.

### 관련 문서

- [persistence.md](persistence.md) — 트랜잭션 전파, Soft Delete 배선, 마이그레이션
- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계
- [api-response.md](api-response.md) — Repository 반환 형식과 API 응답의 관계
- [domain-events.md](domain-events.md) — Repository에서 Outbox 저장
