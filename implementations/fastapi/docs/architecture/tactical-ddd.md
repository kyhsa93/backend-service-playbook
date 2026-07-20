# 전술적 설계 — Aggregate, Entity, Value Object, Domain Event

> 프레임워크 무관 원칙: [../../../../docs/architecture/tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md)

이 저장소는 Python 객체를 세 가지 다른 방식으로 모델링한다 — 각각 Aggregate Root, Entity, Value Object의 성격 차이를 그대로 반영한다.

| 개념 | 이 저장소의 구현 방식 | 이유 |
|------|----------------------|------|
| Aggregate Root | 일반 클래스 (`__init__` + 메서드) | 내부 상태가 시간에 따라 변하고, 변경은 메서드를 통해서만 허용되어야 한다 — `@dataclass`의 자동 생성 `__init__`은 필드를 자유롭게 덮어쓸 수 있어 불변식 보호에 적합하지 않다 |
| Entity (하위 개체) | `@dataclass(frozen=True)` | 생성 이후 값이 바뀌지 않는 하위 레코드 — 식별자(`transaction_id`)로 동등성이 결정된다 |
| Value Object | `@dataclass(frozen=True)` | 불변, 식별자 없음, 속성 조합으로 동등성 결정 |
| Domain Event | `@dataclass(frozen=True)` | 과거에 일어난 사실의 불변 기록 |

---

## Aggregate Root — `Account`

`src/account/domain/account.py`의 `Account`는 비즈니스 규칙과 불변식을 캡슐화하는 일반 클래스다. 외부에서 `account.balance = ...`처럼 속성을 직접 바꿀 수 없게 강제하지는 않지만(Python은 진짜 private이 없다), **모든 상태 변경은 도메인 메서드(`deposit`, `withdraw`, `suspend`, `reactivate`, `close`)를 통해서만 이루어지도록 설계**되어 있고, 호출부(Application Handler)는 이 메서드만 사용한다.

`@property`+`@x.setter` 쌍으로 외부에서 직접 대입 가능한 쓰기용 프로퍼티를 두지 않는 것도 이 컨벤션의 일부다 — harness의 `aggregate-no-public-setters` 규칙이 `domain/` 클래스에 public `@x.setter`가 있는지 AST로 검사한다(Python에 진짜 접근 제어가 없으므로, 이 규칙은 명확히 식별 가능한 `@x.setter` 데코레이터만 좁게 잡는다).

```python
# src/account/domain/account.py
class Account:
    def __init__(
        self, account_id: str, owner_id: str, email: str, balance: Money,
        status: AccountStatus, created_at: datetime, updated_at: datetime,
    ) -> None:
        self.account_id = account_id
        self.owner_id = owner_id
        self.email = email
        self.balance = balance
        self.status = status
        self.created_at = created_at
        self.updated_at = updated_at
        self._events: list[AccountDomainEvent] = []
        self._pending_transactions: list[Transaction] = []

    @classmethod
    def create(cls, owner_id: str, currency: str, email: str) -> Account:
        now = datetime.utcnow()
        account = cls(account_id=str(uuid.uuid4()), owner_id=owner_id, email=email,
                       balance=Money(0, currency), status=AccountStatus.ACTIVE, created_at=now, updated_at=now)
        account._events.append(AccountCreated(...))
        return account

    def withdraw(self, amount: int) -> Transaction:
        if self.status != AccountStatus.ACTIVE:
            raise WithdrawRequiresActiveAccountError()
        if amount <= 0:
            raise InvalidAmountError()
        money = Money(amount, self.balance.currency)
        if self.balance.is_less_than(money):
            raise InsufficientBalanceError()
        self.balance = self.balance.subtract(money)
        self.updated_at = datetime.utcnow()
        transaction = Transaction.create(self.account_id, "WITHDRAWAL", money)
        self._pending_transactions.append(transaction)
        self._events.append(MoneyWithdrawn(...))
        return transaction
```

**핵심 원칙이 지켜지는 방식:**
- **불변식은 도메인 메서드 진입 시 즉시 검증**: 정지된 계좌 출금(`WithdrawRequiresActiveAccountError`), 잔액 부족(`InsufficientBalanceError`), 음수/0 금액(`InvalidAmountError`) 모두 상태 변경 전에 검증하고 실패 시 즉시 예외를 던진다.
- **팩토리 classmethod로 생성**: `Account.create()`가 유일한 생성 경로이며, 생성 시점에 `AccountCreated` 이벤트를 함께 기록한다. `Account.__init__`은 (Repository가 DB row를 복원할 때 쓰는) 저수준 생성자다.
- **다른 Aggregate는 ID로만 참조한다**: 같은 BC 안의 다른 Aggregate(`src/payment/domain/payment.py`의 `Payment`와 `refund.py`의 `Refund`)든, 다른 BC의 Aggregate(`card`가 `payment`의 Aggregate를 참조하는 경우)든 서로 `payment_id: str` 같은 ID로만 참조하고 객체 참조는 하지 않는다 — 루트 [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md) "다른 Aggregate는 ID 참조만 허용한다(객체 참조 금지)" 원칙. 같은 BC 내부는 harness `no-cross-aggregate-reference` 규칙(`src/payment/domain/{payment.py,refund.py}` 대상, [layer-architecture.md](layer-architecture.md) "Domain Service" 절 참고)이, BC 사이는 `no-cross-bc-domain-import` 규칙(`../../harness/rules/no_cross_bc_domain_import.py`, `src/<bc>/domain/*.py`가 다른 BC의 `domain/`을 직접 import하면 실패)이 각각 검사한다.

---

## Entity (하위 개체) — `Transaction`

`src/account/domain/transaction.py`의 `Transaction`은 `Account` Aggregate에 속한 하위 개체다. `frozen=True` dataclass로 구현되어 있지만, `transaction_id`라는 고유 식별자를 가지므로 Value Object가 아니라 Entity다 — 생성 후 값이 바뀌지 않는다는 점에서 "불변 Entity"에 가깝다(입출금 기록은 생성된 이후 절대 수정되지 않는 사실이므로 이 모델링이 적절하다).

```python
# src/account/domain/transaction.py
@dataclass(frozen=True)
class Transaction:
    transaction_id: str
    account_id: str
    type: TransactionType
    amount: Money
    created_at: datetime

    @classmethod
    def create(cls, account_id: str, type: TransactionType, amount: Money) -> Transaction:
        return cls(transaction_id=str(uuid.uuid4()), account_id=account_id, type=type,
                    amount=amount, created_at=datetime.utcnow())
```

`Account`가 `pull_pending_transactions()`로 새로 생성된 `Transaction`을 꺼내 Repository에 전달한다 — Entity는 Aggregate Root를 통해서만 저장/조회된다는 원칙을 지킨다.

---

## Value Object — `Money`

`src/account/domain/money.py`의 `Money`는 식별자가 없고, `amount`와 `currency`의 조합으로만 동등성이 결정되는 불변 객체다.

```python
# src/account/domain/money.py
@dataclass(frozen=True)
class Money:
    amount: int
    currency: str

    def __post_init__(self) -> None:
        if self.amount < 0:
            raise InvalidMoneyAmountError()

    def add(self, other: Money) -> Money:
        self._assert_same_currency(other)
        return Money(self.amount + other.amount, self.currency)

    def is_zero(self) -> bool:
        return self.amount == 0
```

`@dataclass(frozen=True)`는 Python에서 불변 Value Object를 표현하는 관용적인 방법이다 — 필드 재할당이 `FrozenInstanceError`를 일으키므로 생성 후 상태 변경이 구조적으로 불가능하다. `add()`/`subtract()`는 기존 인스턴스를 변경하지 않고 새 `Money`를 반환한다. `__post_init__`에서 불변식(금액은 음수 불가)을 생성 시점에 검증한다 — Value Object의 불변식은 생성자에서 한 번만 검증하면 이후 변경 경로가 없으므로 영구히 유지된다.

**Value Object 사용 기준**: 속성만으로 의미를 표현할 수 있고 식별자가 불필요한 경우. `Money`(금액+통화), 향후 추가될 `Address`, `PhoneNumber` 등이 후보다.

---

## Domain Event — `events.py`

`src/account/domain/events.py`의 모든 이벤트가 `@dataclass(frozen=True)` + 과거형 이름(`AccountCreated`, `MoneyDeposited`, `AccountSuspended` 등) 규칙을 따른다.

```python
@dataclass(frozen=True)
class MoneyDeposited:
    account_id: str
    transaction_id: str
    email: str
    amount: Money
    balance_after: Money
    created_at: datetime
```

Domain Event는 `Union` 타입으로 묶여 `pull_events()`의 반환 타입을 구성한다.

```python
# account.py
AccountDomainEvent = Union[
    AccountCreated, MoneyDeposited, MoneyWithdrawn, AccountSuspended, AccountReactivated, AccountClosed
]
```

→ 수집·저장·발행 흐름 상세는 [domain-events.md](domain-events.md) 참조.

---

## Aggregate 경계 결정 기준 — `Account`와 `Transaction`을 하나로 묶은 이유

- **함께 생성되고 생명주기를 공유한다**: `Transaction`은 `Account.deposit()`/`withdraw()` 호출 없이는 존재할 수 없다.
- **불변식이 서로 얽혀 있다**: 출금 가능 여부(`balance.is_less_than()`)는 `Account`의 현재 잔액을 봐야 판단할 수 있고, 그 판단과 `Transaction` 생성이 원자적으로 일어나야 한다.
- **참조 빈도**: `Transaction` 목록은 항상 특정 `Account` 문맥에서만 조회된다(`find_transactions(account_id, ...)`) — 독립적으로 조회되는 별도 Aggregate로 분리할 이유가 없다.

반대로 `Account`와 `owner`(계좌 소유자)를 같은 Aggregate로 묶지 않은 이유는, 소유자 정보 변경이 계좌의 잔액/상태 불변식에 영향을 주지 않기 때문이다 — `owner_id`라는 ID 참조만 갖는다.

---

## 설계 원칙 요약

| 원칙 | 이 저장소에서의 적용 |
|---|---|
| 비즈니스 규칙은 Aggregate에 | `Account.deposit/withdraw/suspend/reactivate/close`에 캡슐화 |
| 트랜잭션 경계 = Aggregate 경계 | 한 번의 `save()` 호출이 `Account` + 하위 `Transaction`을 함께 커밋 |
| 에러 메시지는 타입화 | `domain/errors.py`의 예외 클래스 (자유 문자열 대신 클래스로 구분) — [error-handling.md](error-handling.md) |
| Domain은 프레임워크 무의존 | `fastapi`/`sqlalchemy`/`aioboto3` import 없음 |
| 변경 가능한 상태 → 일반 클래스, 불변 값 → `frozen dataclass` | `Account` vs `Transaction`/`Money`/이벤트들 |

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 역할, 의존 방향
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 Repository
- [domain-events.md](domain-events.md) — Domain Event 발행·수신, Outbox
- [aggregate-id.md](aggregate-id.md) — ID 생성 규칙
