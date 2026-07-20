# 전술적 설계 (Go) — Aggregate, Entity, Value Object, Domain Event

원칙은 루트 [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md)를 따른다. Go에는 클래스가 없다 — 모든 것은 **struct + 그 struct를 리시버로 갖는 메서드**로 표현한다. `internal/domain/account/` 패키지가 네 가지 개념(Aggregate Root, Entity, Value Object, Domain Event)을 이미 충실히 구현하고 있다. 이 문서는 그 코드를 근거로 각 개념을 설명하고, Go 고유의 제약(진짜 캡슐화의 부재)을 명확히 짚는다.

---

## Aggregate Root — `Account` (`internal/domain/account/account.go`)

```go
type Account struct {
	AccountID    string
	OwnerID      string
	Email        string
	Balance      Money
	Status       Status
	CreatedAt    time.Time
	UpdatedAt    time.Time
	events       []DomainEvent   // 소문자 시작 — 패키지 밖에서 직접 접근 불가
	transactions []Transaction   // 소문자 시작 — 마찬가지
}
```

- **불변식은 도메인 메서드 내부에서만 검증한다.** `Deposit`, `Withdraw`, `Suspend`, `Reactivate`, `Close`가 상태를 바꾸는 유일한 경로다.

```go
func (a *Account) Withdraw(amount int64) (Transaction, error) {
	if a.Status != StatusActive {
		return Transaction{}, ErrWithdrawRequiresActiveAccount
	}
	if amount <= 0 {
		return Transaction{}, ErrInvalidAmount
	}
	money, err := NewMoney(amount, a.Balance.Currency)
	if err != nil {
		return Transaction{}, err
	}
	if a.Balance.LessThan(money) {
		return Transaction{}, ErrInsufficientBalance
	}
	newBalance, err := a.Balance.Subtract(money)
	if err != nil {
		return Transaction{}, err
	}
	a.Balance = newBalance
	tx := newTransaction(a.AccountID, TransactionTypeWithdrawal, money)
	a.transactions = append(a.transactions, tx)
	a.events = append(a.events, MoneyWithdrawn{ /* ... */ })
	return tx, nil
}
```

- **생성과 복원을 분리한다** — `New(...)`는 ID를 새로 발급하고 `AccountCreated` 이벤트를 쌓는 "진짜 생성"이고, `Reconstitute(...)`는 DB에서 읽은 값을 그대로 채우기만 하는 "복원"이다. 이 구분이 root의 "Aggregate 경계 = 트랜잭션 경계"를 지키는 전제 조건이다 — 복원 시점에는 이벤트를 다시 발생시키면 안 되기 때문이다.

```go
func New(ownerID, email, currency string) *Account { /* ID 새로 발급 + AccountCreated 이벤트 */ }
func Reconstitute(accountID, ownerID, email string, balance Money, status Status, createdAt, updatedAt time.Time) *Account {
	/* 이벤트 없이 상태만 복원 */
}
```

---

## Entity — `Transaction` (`internal/domain/account/transaction.go`)

**고유 식별자(`TransactionID`)로 동등성을 판단**하는 객체다. Go에는 `equals()`를 강제하는 인터페이스가 없으므로, 필요하면 명시적으로 메서드를 추가한다(현재는 값 비교가 필요한 곳이 없어 생략되어 있다).

```go
type Transaction struct {
	TransactionID string
	AccountID     string
	Type          TransactionType
	Amount        Money
	CreatedAt     time.Time
}

func newTransaction(accountID string, txType TransactionType, amount Money) Transaction {
	return Transaction{
		TransactionID: uuid.NewString(), // 알려진 격차 — aggregate-id.md 참고
		AccountID:     accountID,
		Type:          txType,
		Amount:        amount,
		CreatedAt:     time.Now(),
	}
}
```

`newTransaction`이 비공개(소문자)인 것이 중요하다 — `Transaction`은 `Account`를 통해서만 생성된다. 패키지 밖에서 `account.Transaction{TransactionID: "x", ...}`처럼 리터럴로 직접 만들 수는 있지만(Go의 구조체 리터럴은 필드가 공개면 항상 조립 가능), `account` 패키지 자신의 코드에서 `Account`를 거치지 않고 `Transaction`을 발급하는 경로는 없다.

---

## Value Object — `Money` (`internal/domain/account/money.go`)

**속성 조합으로 동등성을 판단하는 불변 객체.** 식별자가 없다. Go의 값 타입(포인터가 아닌 struct)은 대입 시 복사되므로 "불변"을 언어가 어느 정도 도와준다 — 메서드가 항상 새 `Money`를 반환하고 원본을 수정하지 않는 스타일로 불변성을 지킨다.

```go
type Money struct {
	Amount   int64
	Currency string
}

func (m Money) Add(other Money) (Money, error) {
	if m.Currency != other.Currency {
		return Money{}, ErrCurrencyMismatch
	}
	return Money{Amount: m.Amount + other.Amount, Currency: m.Currency}, nil // 새 값 반환, m은 그대로
}

func (m Money) Equals(other Money) bool {
	return m.Amount == other.Amount && m.Currency == other.Currency
}
```

메서드 리시버가 `(m Money)`(값 리시버)인 것이 의도적이다 — 포인터 리시버(`(m *Money)`)를 쓰면 메서드 내부에서 원본을 변형할 수 있게 되어 불변 객체의 의미가 흐려진다. `Add`/`Subtract`가 에러를 반환하는 것도 Go 관용이다 — 예외가 없으므로 "통화 불일치"라는 불변식 위반을 반환값으로 표현한다.

---

## Domain Event — `events.go`

과거형 이름(`AccountCreated`, `MoneyDeposited`, `AccountSuspended`)을 쓰는 것은 root 원칙과 동일하다. Go는 union 타입이 없으므로, "이 이벤트들 중 하나"라는 관계를 **빈 마커 메서드를 공유하는 인터페이스**로 표현한다:

```go
type DomainEvent interface {
	isAccountDomainEvent()
}

type AccountCreated struct{ /* ... */ }
func (AccountCreated) isAccountDomainEvent() {}
```

소비 측(`notification/service.go`)은 타입 스위치로 실제 이벤트 종류를 구분한다 — TypeScript의 `instanceof` 체이닝과 동일한 역할을 Go의 `switch e := event.(type)`가 담당한다:

```go
func describe(event account.DomainEvent) (string, emailContent, bool) {
	switch e := event.(type) {
	case account.AccountCreated:
		return "AccountCreated", emailContent{ /* e.AccountID 등 사용 */ }, true
	case account.MoneyDeposited:
		return "MoneyDeposited", emailContent{ /* ... */ }, true
	// ...
	default:
		return "", emailContent{}, false
	}
}
```

---

## Go 고유 제약 — 진짜 캡슐화가 없다

TypeScript/Java/Kotlin은 `private` 키워드로 클래스 인스턴스 단위 캡슐화를 강제한다. **Go는 패키지 단위로만 캡슐화한다** — 소문자로 시작하는 식별자(`events`, `transactions`, `newTransaction`)는 "다른 패키지에서 접근 불가"일 뿐, **같은 `account` 패키지 안의 다른 파일/타입에서는 얼마든지 접근 가능**하다. 즉:

- `Account.events` 필드는 `account` 패키지 밖(예: `internal/application/command`)에서는 절대 직접 읽거나 쓸 수 없다 — `DomainEvents()`/`ClearEvents()` 메서드를 통해서만 접근한다. 이 경계는 확실히 지켜진다.
- 하지만 같은 `account` 패키지 안에 실수로 다른 코드(예: 새로 추가하는 헬퍼 함수)가 `a.events = nil`을 직접 실행하는 것은 컴파일러가 막아주지 않는다. "패키지 == 캡슐화 경계"라는 이 규칙을 팀이 규율로 지켜야 한다.
- 이것이 **Aggregate 하나당 패키지를 분리하는 것이 유리한 이유**다 — `internal/domain/account/` 패키지 안에 `Account`, `Transaction`, `Money`, `DomainEvent` 관련 타입을 모두 모아두면, 그 패키지 경계 자체가 "이 Aggregate의 불변식을 책임지는 코드"의 경계와 일치한다. 여러 Aggregate를 같은 패키지에 두면 이 경계가 흐려진다.

새로 도메인을 추가할 때는 이 제약을 팀 컨벤션(코드 리뷰에서 "Aggregate 필드를 패키지 내부에서도 메서드를 거치지 않고 직접 건드리지 않는다")으로 보완해야 한다는 점을 분명히 인지한다.

---

## Aggregate 경계 결정 기준

root와 동일한 기준을 따른다 — 함께 생성/삭제되고 불변식을 공유하는 객체는 같은 Aggregate로 묶는다. `Account`와 `Transaction`이 그 예다: `Transaction`은 `Account.Deposit()`/`Withdraw()`를 통해서만 생성되고, `Account`의 `Balance` 불변식과 분리해서 존재할 수 없다. 반대로 서로 다른 Aggregate(예: `Account`와 `Payment`, 또는 같은 Payment BC 안의 `Payment`와 `Refund`)는 ID 참조(`PaymentID string` 등)로만 연결하고 객체 참조를 두지 않는다 — `internal/domain/payment/payment.go`와 `refund.go`가 이 규칙의 실제 예시다(root [domain-service.md](../../../../docs/architecture/domain-service.md)의 `RefundEligibilityService`).

이 경계는 `implementations/go/harness/no_cross_aggregate_reference.go`(`no-cross-aggregate-reference` 규칙)가 자동으로 검사한다 — `Payment`가 `Refund` 타입을, `Refund`가 `Payment` 타입을 struct 필드로 직접 갖고 있으면 FAIL로 잡아낸다(ID 문자열 필드는 통과).

같은 원칙을 Bounded Context 경계 전체로 일반화한 규칙이 `implementations/go/harness/no_cross_bc_domain_import.go`(`no-cross-bc-domain-import` 규칙)다 — `internal/domain/<bc>/*.go`가 다른 BC의 `internal/domain/<other-bc>` 패키지를 import하면 FAIL로 잡아낸다(예: `card`가 `payment`를 직접 import). `no-cross-aggregate-reference`가 같은 BC(payment) 안 Payment↔Refund만 정밀 검사하는 것과 달리, 이 규칙은 모든 BC 쌍에 걸쳐 domain 패키지 간 import 자체를 막는다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Domain 레이어의 위치와 의존 방향
- [aggregate-id.md](aggregate-id.md) — ID 발급 규칙과 현재 코드의 격차
- [domain-events.md](domain-events.md) — 이벤트 수집 이후의 Outbox 처리
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 Repository
- [error-handling.md](error-handling.md) — 도메인 메서드의 에러 반환 패턴
