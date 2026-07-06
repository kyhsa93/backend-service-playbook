# 전술적 설계 (Spring Boot / Java)

> 프레임워크 무관 원칙은 루트 [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md) 참고.

## Aggregate Root — `Account`

Aggregate Root는 비즈니스 규칙과 불변식을 캡슐화하고, 상태 변경은 도메인 메서드를 통해서만 이루어진다.

```java
// account/domain/Account.java — 실제 코드 (일부)
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;                       // JPA 대리키 — 도메인 식별자 아님

    @Column(nullable = false, unique = true)
    private String accountId;              // 도메인 식별자

    @Embedded
    private Money balance;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    @Transient
    private final List<Object> domainEvents = new ArrayList<>();

    protected Account() {}                 // JPA가 요구하는 기본 생성자 — protected로 외부 직접 생성 차단

    public static Account create(String ownerId, String email, String currency) {
        Account account = new Account();
        account.accountId = UUID.randomUUID().toString();
        account.ownerId = ownerId;
        account.email = email;
        account.balance = new Money(0, currency);
        account.status = AccountStatus.ACTIVE;
        account.domainEvents.add(new AccountCreatedEvent(/* ... */));
        return account;
    }

    public Transaction deposit(long amount) {
        if (this.status != AccountStatus.ACTIVE) {
            throw new AccountException(AccountException.ErrorCode.DEPOSIT_REQUIRES_ACTIVE_ACCOUNT, "활성 상태의 계좌만 입금할 수 있습니다.");
        }
        if (amount <= 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_AMOUNT, "금액은 0보다 커야 합니다.");
        }
        Money money = new Money(amount, this.balance.currency());
        this.balance = this.balance.add(money);
        this.updatedAt = LocalDateTime.now();
        Transaction transaction = Transaction.create(this.accountId, TransactionType.DEPOSIT, money);
        this.pendingTransactions.add(transaction);
        this.domainEvents.add(new MoneyDepositedEvent(/* ... */));
        return transaction;
    }
}
```

**핵심 원칙의 실현:**
- **정적 팩토리 메서드(`create()`)로만 생성** — public 생성자를 열지 않고 `protected Account() {}`로 막는다. `create()`가 초기 불변식(잔액 0, 상태 ACTIVE)을 보장하는 유일한 경로다. 상세는 [aggregate-id.md](aggregate-id.md) 참고.
- **불변식 위반 시 도메인 메서드 내부에서 즉시 예외** — `deposit()`이 상태/금액을 검증한 뒤가 아니면 상태를 변경하지 않는다.
- **다른 Aggregate는 ID로만 참조** — `Account`는 `ownerId`(사용자 식별자)만 문자열로 갖고, User 객체를 참조하지 않는다.
- **트랜잭션 경계 = Aggregate 경계** — `deposit()` 한 번의 호출이 `Account`(잔액)와 `Transaction`(하위 Entity, pending 목록에 추가) 상태를 함께 바꾸고, `save()` 한 번으로 함께 커밋된다.

`@Entity`로 JPA와 결합되어 있는 것은 [layer-architecture.md](layer-architecture.md)에서 설명하는 알려진 gap이다. Aggregate 설계(팩토리 메서드, 불변식, 이벤트 수집) 자체는 root 원칙과 완전히 일치한다.

---

## Entity — `Transaction`

Entity는 고유 식별자로 동등성을 판단하며 생명주기를 갖는다. `Transaction`은 `Account`의 하위 Entity로, Aggregate Root를 통해서만 생성/접근된다.

```java
// account/domain/Transaction.java — 실제 코드
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;          // 도메인 식별자 — 동등성 기준

    @Column(nullable = false)
    private String accountId;              // 소속 Aggregate Root의 ID (참조)

    @Embedded
    private Money amount;

    protected Transaction() {}

    static Transaction create(String accountId, TransactionType type, Money amount) {   // package-private
        Transaction transaction = new Transaction();
        transaction.transactionId = UUID.randomUUID().toString();
        transaction.accountId = accountId;
        transaction.type = type;
        transaction.amount = amount;
        transaction.createdAt = LocalDateTime.now();
        return transaction;
    }
}
```

**`static Transaction create(...)`가 `public`이 아니라 package-private인 이유**: `Transaction`은 `Account.deposit()`/`Account.withdraw()` 내부에서만 생성되어야 하며, 외부(Application Service 등)가 `Transaction`을 직접 만들어 Aggregate 불변식을 우회하는 것을 막는다. 같은 패키지(`account.domain`) 안에서만 호출 가능하다는 Java의 package-private 접근 제어자가 이 제약을 컴파일 타임에 강제한다.

---

## Value Object — `Money`

Value Object는 속성의 조합으로 동등성을 판단하는 불변 객체다. Java `record`가 이를 정확히 표현한다 — 필드 기반 `equals()`/`hashCode()`가 자동 생성되고, 모든 필드가 `final`이라 생성 후 변경이 불가능하다.

```java
// account/domain/Money.java — 실제 코드
@Embeddable
public record Money(long amount, String currency) {

    public Money {                          // compact canonical constructor — 생성 시 항상 검증
        if (amount < 0) {
            throw new AccountException(AccountException.ErrorCode.INVALID_MONEY_AMOUNT, "금액은 0 이상이어야 합니다.");
        }
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(this.amount + other.amount, this.currency);   // 새 인스턴스 반환 — 원본 불변
    }

    public boolean isLessThan(Money other) {
        assertSameCurrency(other);
        return this.amount < other.amount;
    }

    private void assertSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new AccountException(AccountException.ErrorCode.CURRENCY_MISMATCH, "통화가 일치하지 않습니다.");
        }
    }
}
```

- **compact canonical constructor**(`public Money { ... }`, 괄호와 파라미터 목록 생략)로 모든 생성 경로(정적 팩토리, 역직렬화 등)에 검증이 적용된다 — 필드를 직접 대입하는 별도 생성자를 추가로 만들 수 없다.
- **연산은 새 인스턴스를 반환**한다(`add`, `subtract`) — `this.amount += ...`처럼 필드를 직접 변경하지 않는다. record의 필드는 애초에 `final`이라 컴파일러가 이를 강제한다.
- **`@Embeddable`**로 JPA가 `Account`/`Transaction`의 컬럼에 인라인 매핑한다 — Value Object가 곧 DB 컬럼 그룹으로 대응되는 것도 layer-architecture.md가 설명하는 도메인/ORM 결합의 일부다.

**record를 Value Object에 쓰는 이유**: Java record는 언어 차원에서 "불변 + 값 동등성"을 보장하므로, 직접 `equals()`/`hashCode()`/`toString()`을 작성할 필요가 없다. Kotlin의 `data class`, TypeScript의 `readonly` 필드 클래스에 대응하는 Java의 관용구다.

---

## Domain Event — `MoneyDepositedEvent` 등

과거형 이름의 불변 record로 표현한다.

```java
// account/domain/MoneyDepositedEvent.java — 실제 코드 형태
public record MoneyDepositedEvent(
        String accountId, String email, String transactionId,
        Money amount, Money balanceAfter, LocalDateTime occurredAt) {}
```

`Account`가 `@Transient List<Object> domainEvents`에 이벤트를 수집하고, `pullDomainEvents()`로 꺼낸 뒤 비운다:

```java
public List<Object> pullDomainEvents() {
    List<Object> events = new ArrayList<>(this.domainEvents);
    this.domainEvents.clear();
    return events;
}
```

`List<Object>`로 타입을 지우는 대신, 이벤트 개수가 늘어나면 공통 인터페이스(`sealed interface AccountDomainEvent`, Java 17+)로 묶어 `instanceof` 패턴 매칭의 완전성을 어느 정도 확보할 수 있다 — Kotlin의 `sealed interface` + `when` 완전성 검사에 대응하는 Java 방식이다. 다만 Java의 `sealed` + `switch` 패턴 매칭(JDK 21)은 아직 이 저장소에 도입되지 않았다.

Domain Event의 발행 경로(현재: 동기 `ApplicationEventPublisher`, 올바른 패턴: Outbox)는 [domain-events.md](domain-events.md)에서 상세히 다룬다.

---

## Aggregate 경계 — `Account`와 `Transaction`을 묶은 이유

- **함께 생성되고 생명주기를 공유**: `Transaction`은 `deposit()`/`withdraw()` 호출의 부산물로만 생성되며 독립적으로 존재할 수 없다.
- **불변식을 항상 함께 검증**: 잔액(`balance`)과 거래 내역(`Transaction`)은 항상 짝을 이뤄야 한다 — 거래 기록 없이 잔액만 바뀌거나 그 반대는 불변식 위반이다.
- **`ownerId`로 User를 참조**하지, `User` 객체를 필드로 갖지 않는다 — 다른 Aggregate(사용자 도메인이 있다면)와 결합을 피한다.

`notification/infrastructure/persistence/SentEmail`은 `Account`와 별개의 Aggregate가 아니다 — 비즈니스 불변식이 없는 단순 이력 기록이라 애초에 Aggregate Root로 모델링하지 않았다([directory-structure.md](directory-structure.md) 참고).

---

## 원칙 요약

| 원칙 | 이 저장소에서의 구현 |
|---|---|
| 비즈니스 규칙은 Aggregate에 | `Account.deposit()`/`withdraw()`/`suspend()` 등이 검증 + 상태 변경 + 이벤트 수집을 모두 수행 |
| 정적 팩토리로만 생성 | `Account.create()`, 생성자는 `protected` |
| 하위 Entity는 package-private 생성 | `Transaction.create()`가 `static` package-private |
| Value Object는 `record` | `Money` — compact constructor로 항상 검증 |
| Domain Event는 과거형 `record` | `MoneyDepositedEvent`, `AccountClosedEvent` 등 |
| 에러 메시지는 타입화 | `AccountException.ErrorCode` enum, [error-handling.md](error-handling.md) 참고 |

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Domain 레이어의 JPA 결합 트레이드오프
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 Repository
- [domain-events.md](domain-events.md) — Domain Event 발행·수신, Outbox
- [aggregate-id.md](aggregate-id.md) — ID 생성 규칙과 현재 gap
- [error-handling.md](error-handling.md) — 예외 계층, ErrorCode
