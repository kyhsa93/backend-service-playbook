# 전술적 설계 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md) 참조.

## Aggregate Root — `private constructor()` + `companion object` 팩토리

```kotlin
// domain/Account.kt — 실제 코드 (일부). jakarta.persistence import 없음 — 순수 Kotlin.
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

        // Repository가 영속 데이터로부터 복원할 때 사용 — create()와 달리 도메인 이벤트를 만들지 않는다.
        fun reconstitute(accountId: String, /* ... */ status: AccountStatus, createdAt: LocalDateTime, updatedAt: LocalDateTime, deletedAt: LocalDateTime?): Account =
            Account().apply { /* 전달받은 상태를 그대로 재구성 */ }
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

**`private constructor()` + `companion object.create()`**가 root의 "생성 로직을 Aggregate 내부로 캡슐화"를 표현하는 Kotlin 관용구다. domain/이 JPA에 의존하지 않으므로(영속성 매핑은 `infrastructure/persistence/AccountJpaEntity` + `AccountMapper`가 전담, [directory-structure.md](directory-structure.md) 참고) Hibernate용 `protected` 생성자가 필요 없다 — 더 강한 `private`로 외부 생성을 완전히 차단한다.

- `private constructor()`: 외부 코드(`Account()`)로는 빈 인스턴스를 만들 수 없다. 인스턴스 생성 경로는 `companion object`의 팩토리(`create`/`reconstitute`)뿐이다.
- `companion object.create()`: 유일한 **공개** 생성 경로. 불변식(계좌는 항상 0 잔액으로 시작, 통화가 지정되어야 함 등)을 이 안에서 강제하고, 생성 즉시 `AccountCreatedEvent`를 수집한다. 반면 `reconstitute()`는 Repository 구현체가 DB 상태를 복원할 때만 쓰며 이벤트를 만들지 않는다.
- **모든 프로퍼티가 `private set`**: 외부에서 `account.status = AccountStatus.CLOSED`처럼 직접 대입이 불가능하다. 상태 변경은 반드시 `deposit()`/`suspend()`/`close()` 같은 도메인 메서드를 통해서만 이루어진다 — root의 "외부에서 Aggregate 내부 상태를 직접 변경할 수 없다"를 컴파일러가 강제한다.
- **`id: Long?`(JPA surrogate key)는 domain에 없다**: DB 생성 PK는 `AccountJpaEntity`에만 존재하고, 도메인 식별자는 `accountId: String`이다.

각 도메인 메서드가 비즈니스 규칙을 즉시 검증하고 위반 시 `sealed class AccountException`의 구체 타입을 던지는 것도 root 원칙 그대로다 — 상세는 [error-handling.md](error-handling.md).

---

## Entity — 하위 생명주기 객체

```kotlin
// domain/Transaction.kt — 실제 코드 (일부). 순수 Kotlin — JPA import 없음.
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

        // 복원 전용 — JPA 엔티티(TransactionJpaEntity)로부터 재구성할 때 TransactionMapper가 호출.
        fun reconstitute(transactionId: String, accountId: String, type: TransactionType, amount: Money, createdAt: LocalDateTime): Transaction =
            Transaction().apply { /* 전달받은 상태를 그대로 재구성 */ }
    }
}
```

`Transaction`은 `transactionId`라는 고유 식별자로 동등성을 갖는 Entity다. `Account`와 동일하게 `private constructor() + companion object.create()` 팩토리 패턴을 사용해 일관성을 유지하며, JPA 매핑은 `infrastructure/persistence/TransactionJpaEntity` + `TransactionMapper`가 전담한다. `Account.deposit()`/`withdraw()`가 `Transaction.create()`를 호출하고 `pendingTransactions`에 임시 보관했다가, `AccountRepositoryImpl.save()`가 `Account`와 함께 (Mapper로 `TransactionJpaEntity`로 변환해) 저장한다 — Aggregate Root(`Account`)를 통해서만 하위 Entity가 생성/영속화되는 것이 root의 "하위 Entity는 Aggregate Root의 Repository를 통해 함께 저장" 원칙과 일치한다.

---

## Value Object — `data class` + `init` 블록 (Java의 수동 `equals()` 불필요)

```kotlin
// domain/Money.kt — 실제 코드. 순수 data class — JPA import 없음.
// (@Embeddable 컬럼 매핑은 infrastructure/persistence/MoneyEmbeddable이 전담한다.)
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

`data class`가 root의 "Value Object는 속성의 조합으로 동등성을 판단하는 불변 객체"를 **컴파일러가 자동 생성**하는 `equals()`/`hashCode()`/`toString()`으로 구현한다 — TypeScript/Java였다면 직접 작성해야 했던 `equals(other: Money): boolean { return this.amount === other.amount && ... }`가 `data class` 키워드 하나로 대체된다.

- **`init` 블록**: 생성자 호출 시점에 불변식을 검증한다. `Money(-100, "KRW")`는 객체가 만들어지기 전에 예외가 발생한다 — 잘못된 상태의 Value Object가 존재할 수 없다.
- **`val` 프로퍼티만 사용**: 모든 필드가 불변(`val`)이므로 `add()`/`subtract()`는 항상 새 `Money` 인스턴스를 반환한다 — 기존 인스턴스를 변경하지 않는다.
- **`copy()`**: `data class`가 자동 제공하는 `copy(amount = ..., currency = ...)`로 일부 필드만 바꾼 새 인스턴스를 만들 수 있다 — 이 예제에서는 `add()`/`subtract()`가 직접 새 `Money(...)`를 생성하므로 `copy()`를 쓰지 않지만, 필드가 많은 Value Object에서는 `copy()`가 가독성을 높인다.

---

## Domain Event — 과거형 `data class`

```kotlin
// domain/AccountCreatedEvent.kt — 실제 코드
data class AccountCreatedEvent(
    val accountId: String,
    val ownerId: String,
    val email: String,
    val currency: String,
    val createdAt: LocalDateTime,
)
```

과거형 이름(`AccountCreated`, `MoneyDeposited`)과 불변 데이터 클래스라는 root 원칙을 그대로 따른다. `sealed interface`로 이벤트 타입을 묶어 `when` 완전성 검사를 얻는 방법은 [domain-events.md](domain-events.md)에서 다룬다.

---

## Aggregate 경계 — Account와 Transaction을 묶은 이유

`Account`(잔액)와 `Transaction`(거래 내역)은 하나의 Aggregate로 묶여 있다 — `Transaction`은 `Account`의 Repository(`AccountRepository`)를 통해서만 저장/조회된다. 이 경계 판단은 root의 기준과 일치한다.

- **함께 생성/변경되는 불변식**: 입금/출금은 `balance` 갱신과 `Transaction` 기록이 항상 함께 일어나야 한다 — 거래 기록 없이 잔액만 바뀌면 감사 추적이 깨진다.
- **`Money`는 Aggregate 경계가 아니라 Value Object**: 식별자가 없고 `Account.balance`/`Transaction.amount` 양쪽에서 재사용된다(영속 계층에서는 `MoneyEmbeddable`로 `@Embeddable` 내장) — 별도 Aggregate로 분리할 이유가 없다.
- **`notification/`은 별도 Aggregate가 아니라 Technical Service**: 이메일 발송은 비즈니스 불변식이 없는 기술적 부수 효과이므로 Aggregate로 모델링하지 않는다 (→ [directory-structure.md](directory-structure.md)).

---

## 설계 원칙 요약 — Kotlin에서의 표현

| root 원칙 | Kotlin 표현 |
|---|---|
| 비즈니스 규칙은 Aggregate에 캡슐화 | `private set` 프로퍼티 + 도메인 메서드로만 상태 변경 |
| 생성 로직 통제 | `private constructor()` + `companion object.create()` (JPA 무의존이라 protected 불필요) |
| Value Object 동등성 | `data class` (자동 `equals`/`hashCode`/`copy`) |
| 불변식 즉시 검증 | `init` 블록, 도메인 메서드 내부의 `if (...) throw` |
| 에러 타입화 | `sealed class` 예외 계층 ([error-handling.md](error-handling.md)) |
| Domain Event | 과거형 `data class`, `sealed interface`로 계층화 가능 ([domain-events.md](domain-events.md)) |

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Domain 레이어 역할, null-safety의 역할
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 Repository
- [domain-events.md](domain-events.md) — Domain Event 발행·수신, Outbox
- [error-handling.md](error-handling.md) — sealed class 예외 계층 상세
