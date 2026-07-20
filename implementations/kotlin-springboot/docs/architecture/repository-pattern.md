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

## 조회/저장 메서드 네이밍 — `find<Noun>s`/`save<Noun>`으로 통일 (harness `repository-naming` 규칙으로 강제)

```kotlin
// domain/AccountRepository.kt — 실제 코드
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>   // 목록 + count를 함께 반환
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

root 컨벤션이 요구하는 세 가지가 모두 실제 코드에 반영되어 있다.

| 목적 | root 패턴 | 현재 코드 |
|------|--------------|------|
| 목록 조회 | `find<Noun>s` 하나만, 단건도 `take: 1`로 재사용 | `findAccounts` 하나로 통일 — 과거의 `findByAccountIdAndOwnerId`(단건 전용) + `findAll`/`countAll`(목록/count 분리) 세 메서드가 사라졌다 |
| 저장 | `save<Noun>` | `saveAccount` — noun 접미사 포함 |
| 삭제 | `delete<Noun>` | `deleteAccount(accountId)` — 이전부터 일치 |

과거에 있던 `findByAccountIdAndOwnerId`처럼 전용 단건 조회 메서드를 두는 것은 root가 명시적으로 금지하는 "findOne 전용 메서드" 패턴이었다 — 조회 조건이 하나 늘어날 때마다(`findByAccountIdAndStatus` 등) 메서드가 계속 늘어나는 확장성 문제도 있었다. `Pair<List<Account>, Long>`이 root의 `{ orders: Order[], count: number }` 객체 반환에 대응한다 — Kotlin에서는 전용 응답 `data class`(예: `AccountsWithCount(accounts: List<Account>, count: Long)`)를 별도로 선언해도 되고, 호출부가 소수뿐이라면 `Pair`로 충분하다. 필드가 2개뿐이므로 `Pair`가 과하지 않지만, 이후 필드가 늘어날 여지가 있다면 named `data class`가 가독성이 낫다.

`Transaction`(Account Aggregate의 하위 엔티티) 조회도 같은 원칙을 적용해 `findTransactions`/`countTransactions` 두 메서드를 `findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>` 하나로 합쳤다.

```kotlin
// application/command/DepositService.kt — 단건 조회는 take=1로 재사용 (실제 코드)
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
        accountRepository.saveAccount(account)   // @Transactional — Account 저장 + Outbox 적재, 한 트랜잭션
        /* ... */
        // 여기서 끝난다 — Outbox 드레인(OutboxPoller/OutboxConsumer)은 별도 컴포넌트가 담당한다.
    }
}
```

`.firstOrNull()`이 root의 `.pop()` / `.then(r => r.orders.pop())` 패턴에 대응하는 Kotlin 관용구다 — 리스트가 비어 있으면 `null`, `?:`로 즉시 예외를 던진다. Java의 `Optional<T>` 래핑이나 인덱스 접근(`[0]`, `IndexOutOfBoundsException` 위험) 없이 한 줄로 끝난다. 계좌를 조회 후 변경하는 6개 Command Service(`Deposit`/`Withdraw`/`Suspend`/`Close`/`Reactivate`/`Delete`)가 모두 이 패턴을 쓴다. `CreateAccountService`는 신규 Aggregate를 생성하므로 조회 없이 `saveAccount(account)`만 호출한다.

**CQRS 읽기 전용 포트(`AccountQuery`)도 같은 시그니처를 재사용한다.** `GetAccountService`/`GetTransactionsService`는 `AccountRepository`(쓰기 모델)가 아니라 `application/query/AccountQuery`에 의존한다 — Query Service가 `saveAccount`/`deleteAccount` 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제하기 위해서다(harness `cqrs-pattern` 규칙이 실제로 검사한다). `AccountQuery`는 별개 인터페이스지만 `findAccounts(query: AccountFindQuery)`/`findTransactions(query: TransactionFindQuery)`를 `AccountRepository`와 정확히 같은 시그니처로 선언한다 — `AccountRepositoryImpl`이 두 인터페이스를 모두 구현할 때 하나의 override가 양쪽을 동시에 만족시킨다. Card/Payment/Refund의 `*Query` 인터페이스(`CardQuery.findCards`/`PaymentQuery.findPayments`/`RefundQuery.findRefunds`)도 모두 같은 패턴이다 — 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참조.

---

## 구현체

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository, AccountQuery {

    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> {
        val accounts = /* 기존 findAll 로직 재사용 */ /* ... */
        val count = /* 기존 countAll 로직 재사용 */ /* ... */
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
        account.markDeleted()   // Account.markDeleted() — CLOSED 상태가 아니면 DeleteRequiresClosedAccountException
        jpaRepository.save(account)
    }

    override fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long> {
        val transactions = transactionJpaRepository
            .findByAccountIdOrderByCreatedAtDesc(query.accountId, PageRequest.of(query.page, query.take))
            .map(TransactionMapper::toDomain)
        val count = transactionJpaRepository.countByAccountId(query.accountId)
        return transactions to count
    }

    // AccountQuery(읽기 전용 포트)는 findAccounts/findTransactions를 AccountRepository와 정확히 같은
    // 시그니처로 선언하므로, 위의 두 override가 두 인터페이스를 동시에 만족시킨다 — 별도 오버로드 불필요.
}
```

**Repository에 update 메서드를 별도로 두지 않는다** — `AccountRepositoryImpl`에 `updateAccount()` 류의 메서드가 없고, 모든 상태 변경은 `Account.deposit()`/`suspend()`/`close()`/`markDeleted()` 등 도메인 메서드로 이루어진 뒤 `saveAccount()`로 영속화된다.

**`close()`(상태 전환)와 `markDeleted()`(soft delete)는 서로 다른 생명주기 이벤트다.** `Account.close()`는 `AccountStatus.CLOSED`로 상태를 바꿀 뿐 `deletedAt`을 건드리지 않는다 — `CLOSED` 계좌도 `GetAccountService`로 계속 조회 가능해야 하기 때문이다(계좌 이력 확인 등). `deleteAccount()`는 반대로 `deletedAt`을 설정해 이후 모든 조회(`deletedAt IS NULL` 조건)에서 제외시킨다. `Account.markDeleted()`는 `status != CLOSED`이면 `DeleteRequiresClosedAccountException`을 던져, 활성 계좌가 종료 절차 없이 곧바로 삭제되는 것을 막는다 — 삭제는 항상 "종료 → 삭제" 두 단계를 거친다.

---

## 동적 필터

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

값이 있을 때만 조건을 추가하는 방식(`if (!query.accountId.isNullOrBlank())`)이 root의 동적 필터 원칙과 정확히 일치한다. `deletedAt IS NULL` 조건이 모든 조회에 기본 적용되는 것도 soft delete 조회 원칙에 맞다.

---

## 원칙 요약

- **1 Aggregate = 1 Repository 인터페이스 + 구현체**.
- **`delete<Noun>` 추가 완료**: `deleteAccount(accountId)`가 실제 코드에 존재하고 `Account.markDeleted()`를 통해 CLOSED 상태만 삭제 가능하도록 강제한다.
- **`find<Noun>s`/`save<Noun>`으로 통일**: `findByAccountIdAndOwnerId`/`findAll`/`countAll`/`save` → `findAccounts`/`saveAccount`로 리네이밍되었다. `findTransactions`/`countTransactions`도 `findTransactions(query): Pair<...>` 하나로 합쳐졌다. 이 통일은 `AccountRepository`(쓰기 모델)뿐 아니라 `AccountQuery`(읽기 전용 포트)에도 그대로 적용되어 있고, Card(`findCards`/`saveCard`)·Payment(`findPayments`/`savePayment`)·Refund(`findRefunds`/`saveRefund`)·Auth(`CredentialQuery.findCredentials`)의 Repository/Query 양쪽 모두 같은 형태다. 이 규칙은 harness의 `repository-naming` 검사(`domain/`, `application/query/` 안의 `*Repository`/`*Query` 인터페이스 메서드를 스캔, `findBy...`/bare `findAll`/`count*`/`save`/`delete` 를 blocklist로 잡는다)가 자동으로 강제한다 — 문서가 "완료"라고 적어도 실제로 리네이밍이 빠진 인터페이스가 있으면(과거 `CredentialQuery.findByUserId`가 그랬듯) harness FAIL로 드러난다.
- **단건 조회는 `take: 1` + `firstOrNull()`**: 전용 findOne 메서드를 만들지 않는다 — Command Service 6곳이 모두 이 패턴을 쓴다.
- **Repository에 update 메서드 없음**: 상태 변경은 Aggregate 도메인 메서드 + `saveAccount`/`deleteAccount`로 이루어진다.
- **동적 필터, soft-delete 조회 조건**: 값이 있을 때만 조건을 추가하고, `deletedAt IS NULL`이 모든 조회에 기본 적용된다.

### 관련 문서

- [persistence.md](persistence.md) — 트랜잭션 전파, Soft Delete 배선, 마이그레이션
- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계
- [api-response.md](api-response.md) — Repository 반환 형식과 API 응답의 관계
- [domain-events.md](domain-events.md) — Repository에서 Outbox 저장
- [cqrs-pattern.md](cqrs-pattern.md) — 읽기 전용 `AccountQuery` 포트와의 관계
