# 영속성 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root persistence.md](../../../../docs/architecture/persistence.md) 참조.

## 트랜잭션 전파 — `@Transactional` (root의 ThreadLocal 패턴을 Spring이 대체)

root는 언어별 컨텍스트-로컬 저장소(Node `AsyncLocalStorage`, Java/Kotlin `ThreadLocal`)로 트랜잭션 클라이언트를 암묵 전파하라고 규정한다. Spring/JPA에서는 이 메커니즘이 **`@Transactional` + 스레드바운드 커넥션**으로 이미 프레임워크에 내장되어 있어, 별도의 `TransactionManager` 클래스를 직접 구현할 필요가 없다.

```kotlin
// application/command/CreateAccountService.kt — 실제 코드
@Service
@Transactional
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.save(account)   // 같은 스레드 = 같은 트랜잭션의 JDBC 커넥션 자동 재사용
        // ...
    }
}
```

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
        accountRepository.save(sourceAccount)
        ledgerRepository.save(ledgerEntry)
    }
}
```

Query Service는 `@Transactional(readOnly = true)`로 구분한다 — Hibernate의 dirty checking을 생략하고 읽기 전용 커넥션을 사용해 최적화한다.

```kotlin
// application/query/GetAccountService.kt — 실제 코드
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountRepository: AccountRepository) { /* ... */ }
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

## 알려진 갭 — Soft Delete가 배선되어 있지 않다

`deletedAt` 컬럼은 `Account`, `Transaction` 모두에 존재하지만, **`AccountRepository` 인터페이스에 delete 메서드 자체가 없다.**

```kotlin
// domain/AccountRepository.kt — 실제 코드. delete 메서드 없음
interface AccountRepository {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?
    fun findAll(query: AccountFindQuery): List<Account>
    fun countAll(query: AccountFindQuery): Long
    fun save(account: Account)
    fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction>
    fun countTransactions(accountId: String): Long
}
```

계좌 종료(`close()`)는 `AccountStatus.CLOSED`로 상태를 바꿀 뿐 `deletedAt`을 설정하지 않는다 — soft delete 컬럼은 스키마에 존재하지만 실제로 실행되는 경로가 없는, 죽은 컬럼이다. root 원칙에 맞게 배선하려면:

```kotlin
// domain/AccountRepository.kt — 제안: delete<Noun> 추가
interface AccountRepository {
    // ... 기존 메서드 ...
    fun deleteAccount(accountId: String)
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 제안
@Transactional
override fun deleteAccount(accountId: String) {
    val account = jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId) ?: return
    account.markDeleted()   // BaseEntity의 soft delete 메서드
    jpaRepository.save(account)
}
```

```kotlin
// 조회 쿼리는 이미 deletedAt IS NULL 조건을 적용 중 — 실제 코드
override fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account? =
    jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
```

조회 측(`findByAccountIdAndOwnerIdAndDeletedAtIsNull`)은 이미 올바르게 soft-delete를 고려하고 있다 — 삭제 실행 경로만 빠져 있다. 메서드 네이밍을 root 컨벤션(`delete<Noun>`)에 맞추는 것과 함께 다루는 것이 자연스러우므로 상세는 [repository-pattern.md](repository-pattern.md)에서 이어서 다룬다.

---

## 알려진 갭 — 마이그레이션 없이 `ddl-auto: update`

```yaml
# application.yml — 실제 코드
spring:
  jpa:
    hibernate:
      ddl-auto: update
```

Hibernate의 자동 스키마 동기화는 root가 "개발 환경 전용"으로 못 박는 기능이다. 이 예제에는 마이그레이션 파일이 전혀 없다 — 프로덕션에 그대로 반영하면 배포 시 의도치 않은 스키마 변경(컬럼 삭제, 타입 변경 등)이 발생할 수 있다. 예제 성격상 허용되는 단순화이지만, 실제 서비스로 발전시킬 때는 **Flyway**(Java/Kotlin 생태계에서 가장 널리 쓰이는 마이그레이션 도구)를 도입해야 한다.

```kotlin
// build.gradle.kts — 프로덕션 전환 시 추가
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

```yaml
# application.yml — 프로덕션 프로파일
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
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL UNIQUE,
    owner_id VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    balance_amount BIGINT NOT NULL,
    balance_currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);
```

로컬 개발 환경에서는 `ddl-auto: update`(또는 `create-drop`, [testing.md](testing.md)의 E2E 테스트가 실제로 사용)를 유지해도 무방하다 — root도 "자동 동기화는 로컬 전용"이라고 명시한다. 문제는 프로덕션 프로파일에도 동일 설정이 그대로 남아있다는 점이다.

---

## 원칙 요약

- **트랜잭션은 `@Transactional`로 선언한다** — root의 수동 ThreadLocal 전파를 Spring이 대체한다. Command는 `@Transactional`, Query는 `@Transactional(readOnly = true)`.
- **공통 컬럼은 `@MappedSuperclass` BaseEntity로 추출**해 중복을 없앤다 — 현재는 Entity마다 반복 선언.
- **Soft Delete는 컬럼만 있고 배선되어 있지 않다** — `deleteAccount()` 추가 필요 (상세는 [repository-pattern.md](repository-pattern.md)).
- **프로덕션은 Flyway 마이그레이션 필수** — 현재 `ddl-auto: update`는 로컬 전용으로 제한해야 한다.

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리, delete 메서드 설계
- [layer-architecture.md](layer-architecture.md) — 트랜잭션 경계와 레이어 역할
- [testing.md](testing.md) — 테스트에서의 `ddl-auto: create-drop` 사용
