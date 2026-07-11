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

## 알려진 갭 — 조회/저장 메서드 네이밍이 root 컨벤션과 다르다

```kotlin
// domain/AccountRepository.kt — 실제 코드
interface AccountRepository {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?   // ← 단건 전용 메서드
    fun findAll(query: AccountFindQuery): List<Account>                          // ← count와 분리
    fun countAll(query: AccountFindQuery): Long
    fun save(account: Account)
    fun deleteAccount(accountId: String)                                          // ← delete<Noun> 컨벤션 적용됨 (아래 참고)
    fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction>
    fun countTransactions(accountId: String): Long
}
```

root 컨벤션은 다음 세 가지를 요구한다.

| 목적 | root 패턴 | 현재 코드 |
|------|--------------|------|
| 목록 조회 | `find<Noun>s` 하나만, 단건도 `take: 1`로 재사용 | `findByAccountIdAndOwnerId`(단건 전용) + `findAll`(목록) 두 갈래로 분산 — 아직 불일치 |
| 저장 | `save<Noun>` | `save` — noun 접미사 없음(단일 Aggregate뿐이라 모호하지 않지만 컨벤션 불일치) — 아직 불일치 |
| 삭제 | `delete<Noun>` | `deleteAccount(accountId)` — root 컨벤션과 일치. Soft Delete 배선 갭([persistence.md](persistence.md))은 이 메서드 추가로 해소됨 |

`findByAccountIdAndOwnerId`처럼 전용 단건 조회 메서드를 두는 것은 root가 명시적으로 금지하는 "findOne 전용 메서드" 패턴이다 — 조회 조건이 하나 늘어날 때마다(`findByAccountIdAndStatus` 등) 메서드가 계속 늘어나는 확장성 문제도 있다. `findAll`/`save`의 네이밍은 이번 변경 범위에 포함되지 않았고 여전히 남은 갭이다 — 아래 "올바른 형태"는 이 나머지 갭에 대한 제안이다.

---

## 올바른 형태 — `findAccounts` 하나로 통일 (남은 갭)

`deleteAccount`는 이미 실제 코드에 반영되어 아래 제안에서 제외했다 — 실제 시그니처는 위 "알려진 갭" 절 참고. 남은 제안은 `findAll`/`save`의 네이밍뿐이다.

```kotlin
// domain/AccountRepository.kt — 제안 (findAll/save 부분만)
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // 목록 + count를 함께 반환
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)                                   // 실제 코드에 이미 존재 — persistence.md 참조

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

## 구현체 — `deleteAccount`는 실제 코드, `save<Noun>`으로의 리네이밍은 남은 제안

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드 (deleteAccount)
@Transactional
override fun deleteAccount(accountId: String) {
    val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
    account.markDeleted()   // Account.markDeleted() — CLOSED 상태가 아니면 DeleteRequiresClosedAccountException
    jpaRepository.save(account)
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 제안 (findAll/save 이름만 변경, 남은 갭)
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
```

**Repository에 update 메서드를 별도로 두지 않는다** — 이 원칙은 이미 지켜지고 있다. `AccountRepositoryImpl`에 `updateAccount()` 류의 메서드가 없고, 모든 상태 변경은 `Account.deposit()`/`suspend()`/`close()`/`markDeleted()` 등 도메인 메서드로 이루어진 뒤 `save()`로 영속화된다.

**`close()`(상태 전환)와 `markDeleted()`(soft delete)는 서로 다른 생명주기 이벤트다.** `Account.close()`는 `AccountStatus.CLOSED`로 상태를 바꿀 뿐 `deletedAt`을 건드리지 않는다 — `CLOSED` 계좌도 `GetAccountService`로 계속 조회 가능해야 하기 때문이다(계좌 이력 확인 등). `deleteAccount()`는 반대로 `deletedAt`을 설정해 이후 모든 조회(`deletedAt IS NULL` 조건)에서 제외시킨다. `Account.markDeleted()`는 `status != CLOSED`이면 `DeleteRequiresClosedAccountException`을 던져, 활성 계좌가 종료 절차 없이 곧바로 삭제되는 것을 막는다 — 삭제는 항상 "종료 → 삭제" 두 단계를 거친다.

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
- **`delete<Noun>` 추가 완료**: `deleteAccount(accountId)`가 실제 코드에 존재하고 `Account.markDeleted()`를 통해 CLOSED 상태만 삭제 가능하도록 강제한다 — 더 이상 갭 아님.
- **`find<Noun>s`/`save<Noun>`으로 통일**: 현재 `findByAccountIdAndOwnerId`/`findAll`/`save` → `findAccounts`/`saveAccount`로 리네이밍은 아직 남은 갭이다.
- **단건 조회는 `take: 1` + `firstOrNull()`**: 전용 findOne 메서드를 만들지 않는다 — 위 리네이밍과 함께 적용될 남은 제안.
- **Repository에 update 메서드 없음**: 이미 올바름 — 상태 변경은 Aggregate 도메인 메서드 + `save`/`deleteAccount`.
- **동적 필터, soft-delete 조회 조건**: 이미 올바름.

### 관련 문서

- [persistence.md](persistence.md) — 트랜잭션 전파, Soft Delete 배선, 마이그레이션
- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계
- [api-response.md](api-response.md) — Repository 반환 형식과 API 응답의 관계
- [domain-events.md](domain-events.md) — Repository에서 Outbox 저장
