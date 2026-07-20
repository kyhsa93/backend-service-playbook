# 영속성 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root persistence.md](../../../../docs/architecture/persistence.md) 참조.

## 트랜잭션 전파 — `@Transactional` (root의 ThreadLocal 패턴을 Spring이 대체)

root는 언어별 컨텍스트-로컬 저장소(Node `AsyncLocalStorage`, Java/Kotlin `ThreadLocal`)로 트랜잭션 클라이언트를 암묵 전파하라고 규정한다. Spring/JPA에서는 이 메커니즘이 **`@Transactional` + 스레드바운드 커넥션**으로 이미 프레임워크에 내장되어 있어, 별도의 `TransactionManager` 클래스를 직접 구현할 필요가 없다.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository {
    @Transactional
    override fun saveAccount(account: Account) {
        jpaRepository.save(/* ... */)                    // Account 저장
        // ... 하위 Transaction 저장 ...
        outboxWriter.saveAll(account.pullDomainEvents())  // 같은 스레드 = 같은 트랜잭션의 JDBC 커넥션 자동 재사용
    }
}
```

`@Transactional`은 Command Service가 아니라 `Repository.saveAccount()`에 있다 — Account 저장과 Outbox 적재를 하나의 물리 트랜잭션으로 묶는 경계가 바로 여기이기 때문이다([domain-events.md](domain-events.md) 참고).

Spring AOP가 `@Transactional` 메서드 진입 시 트랜잭션을 시작해 현재 스레드에 커넥션을 바인딩하고(`TransactionSynchronizationManager`), 같은 스레드에서 호출되는 모든 Repository 메서드가 자동으로 같은 커넥션/트랜잭션을 사용한다 — root의 `getClient()` 패턴에 해당하는 것을 Spring이 내부적으로 수행한다.

```kotlin
// 여러 Repository를 하나의 트랜잭션으로 묶기 — 메서드에 @Transactional 하나만 붙이면 됨
@Service
@Transactional
class TransferService(
    private val accountRepository: AccountRepository,
    private val ledgerRepository: LedgerRepository,   // 예시 — 다른 Aggregate
) {
    fun transfer(command: TransferCommand) {
        // 두 Repository 호출 모두 같은 트랜잭션 안에서 실행 — 하나라도 예외가 나면 전체 롤백
        accountRepository.saveAccount(sourceAccount)
        ledgerRepository.saveLedger(ledgerEntry)
    }
}
```

Query Service는 `@Transactional(readOnly = true)`로 구분한다 — Hibernate의 dirty checking을 생략하고 읽기 전용 커넥션을 사용해 최적화한다.

```kotlin
// application/query/GetAccountService.kt — 실제 코드
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) { /* ... */ }
```

---

## Entity 공통 컬럼 — 현재는 반복 선언, `@MappedSuperclass`로 개선 가능

```kotlin
// domain/Account.kt, domain/Transaction.kt — 실제 코드 (각 Entity가 반복 선언)
@Column(nullable = false)
var createdAt: LocalDateTime = LocalDateTime.now()
    private set

@Column(nullable = false)
var updatedAt: LocalDateTime = LocalDateTime.now()
    private set

@Column
var deletedAt: LocalDateTime? = null
    private set
```

`Account`와 `Transaction` 모두 이 세 컬럼을 각자 선언한다 — 재사용 가능한 `@MappedSuperclass` 상속이 없다. Aggregate가 2개뿐인 현재 규모에서는 중복이 크지 않지만, 도메인이 늘어나면 아래처럼 공통 베이스로 추출하는 것이 root의 "공통 BaseEntity 상속" 원칙에 맞다.

```kotlin
// common/BaseEntity.kt — 제안
@MappedSuperclass
abstract class BaseEntity {
    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
        protected set

    @Column
    var deletedAt: LocalDateTime? = null
        protected set

    fun markUpdated() { updatedAt = LocalDateTime.now() }
    fun markDeleted() { deletedAt = LocalDateTime.now() }
}
```

```kotlin
// domain/Account.kt — BaseEntity 상속 시
@Entity
@Table(name = "accounts")
class Account protected constructor() : BaseEntity() { /* createdAt/updatedAt/deletedAt 제거, 상속으로 대체 */ }
```

`protected set`으로 열어두는 이유는 하위 Entity(`Account`)가 `markUpdated()`/`markDeleted()`처럼 의도가 드러나는 메서드를 통해서만 값을 바꾸게 하기 위함이다 — 직접 필드를 대입하지 않는다.

---

## Soft Delete — 배선 완료

`deletedAt` 컬럼은 `Account`, `Transaction` 모두에 존재하고, `AccountRepository.deleteAccount(accountId)`가 실제 실행 경로를 제공한다.

```kotlin
// domain/AccountRepository.kt — 실제 코드
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

```kotlin
// domain/Account.kt — 실제 코드. soft delete는 CLOSED 상태에서만 허용된다
fun markDeleted() {
    if (status != AccountStatus.CLOSED) throw DeleteRequiresClosedAccountException()
    deletedAt = LocalDateTime.now()
    updatedAt = deletedAt!!
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드
@Transactional
override fun deleteAccount(accountId: String) {
    val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
    account.markDeleted()
    jpaRepository.save(account)
}
```

```kotlin
// 조회 쿼리는 이미 deletedAt IS NULL 조건을 적용 중 — 실제 코드
// (findAccounts는 AccountRepository/AccountQuery 양쪽이 공유하는 동일 시그니처다)
private fun buildJpql(query: AccountFindQuery, count: Boolean): String {
    val select = if (count) "SELECT COUNT(a)" else "SELECT a"
    val sb = StringBuilder("$select FROM AccountJpaEntity a WHERE a.deletedAt IS NULL")
    /* ... 나머지 동적 필터 조건 ... */
    return sb.toString()
}
```

**`close()`(상태 전환)와 soft delete는 서로 다른 생명주기 이벤트로 분리했다.** `Account.close()`는 `AccountStatus.CLOSED`로 상태만 바꾸고 `deletedAt`은 건드리지 않는다 — `CLOSED` 계좌도 `GetAccountService`로 계속 조회 가능해야 하기 때문이다(모든 조회가 `deletedAt IS NULL`을 조건으로 걸기 때문에, `close()`가 `deletedAt`까지 설정해버리면 종료 직후 계좌를 다시 조회할 방법이 없어진다). 대신 삭제는 `account/application/command/DeleteAccountService.kt`라는 별도 유스케이스(`DELETE /accounts/{accountId}`)로 존재하고, `Account.markDeleted()`가 "이미 CLOSED 상태인 계좌만 삭제 가능"이라는 규칙을 도메인 레벨에서 강제한다 — 활성 계좌를 곧바로 삭제하려 하면 `DeleteRequiresClosedAccountException`(400)을 던진다.

메서드 네이밍(`delete<Noun>`)은 root 컨벤션과 일치한다 — Repository는 `findAccounts`/`saveAccount` 네이밍을 쓴다. 상세는 [repository-pattern.md](repository-pattern.md) 참고.

---

## 마이그레이션 — Flyway로 관리

Hibernate의 자동 스키마 동기화는 root가 "개발 환경 전용"으로 못 박는 기능이다. 이 예제는 Flyway를 도입해 이 원칙을 따른다.

```kotlin
// build.gradle.kts
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

```yaml
# application.yml
spring:
  jpa:
    hibernate:
      ddl-auto: validate   # 스키마 검증만 — 변경은 마이그레이션 파일로만
  flyway:
    enabled: true
    locations: classpath:db/migration
```

```sql
-- src/main/resources/db/migration/V1__create_accounts.sql
CREATE TABLE accounts (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    amount BIGINT,
    currency VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    CONSTRAINT uk_accounts_account_id UNIQUE (account_id)
);

-- V2__create_transactions.sql, V3__create_outbox_events.sql, V4__create_sent_emails.sql — 나머지 3개 테이블도 동일한 방식
```

`amount`/`currency`에 접두어가 없는 이유: `Account.balance`/`Transaction.amount`가 `@Embedded Money`이고 `@AttributeOverride`를 쓰지 않아 Hibernate가 `Money`의 필드명을 그대로 컬럼명으로 쓴다 — 이 마이그레이션은 (아직 고치지 않은) 기존 스키마를 있는 그대로 옮겨 담았다. `outbox_events`/`sent_emails`는 각각 자체 `id` identity PK + unique `event_id`/`sent_email_id` 조합을 쓴다(Java 버전의 `outbox`/`sent_email`과 미묘하게 다른 엔티티 설계 — Kotlin 쪽은 outbox 행 자체의 식별자와 DB 내부 PK를 분리했다).

실제로 빈 DB에 앱을 기동해 Flyway가 4개 마이그레이션을 자동 적용하고 `ddl-auto: validate`가 통과하는 것까지 확인함.

**테스트 환경은 예외**: `AccountControllerE2ETest`/`NotificationE2ETest`의 `@DynamicPropertySource`가 `ddl-auto: create-drop` + `spring.flyway.enabled: false`를 쓰는 것은 root가 명시한 대로 "개발/테스트 전용" 허용 범위 안에 있다 — Testcontainers가 매 테스트 실행마다 새 컨테이너를 띄우므로 자동 스키마 생성이 오히려 적절하고, Flyway와 동시에 켜면 스키마가 중복 생성되므로 테스트에서는 Flyway를 꺼둔다.

---

## 원칙 요약

- **트랜잭션은 `@Transactional`로 선언한다** — root의 수동 ThreadLocal 전파를 Spring이 대체한다. Command는 `@Transactional`, Query는 `@Transactional(readOnly = true)`.
- **공통 컬럼은 `@MappedSuperclass` BaseEntity로 추출**해 중복을 없앤다 — 현재는 Entity마다 반복 선언.
- **Soft Delete 배선 완료** — `AccountRepository.deleteAccount()` + `Account.markDeleted()`(CLOSED 상태만 허용) + `DeleteAccountService`/`DELETE /accounts/{accountId}` (상세는 [repository-pattern.md](repository-pattern.md)).
- **Flyway 마이그레이션 도입 완료** — `ddl-auto: validate` + `db/migration/`.

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리, delete 메서드 설계
- [layer-architecture.md](layer-architecture.md) — 트랜잭션 경계와 레이어 역할
- [testing.md](testing.md) — 테스트에서의 `ddl-auto: create-drop` 사용
