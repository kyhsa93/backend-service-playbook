# 전술적 설계 — Aggregate, Entity, Value Object (NestJS)

> 개념 정의와 경계 기준은 root [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md)를 참조한다. 이 문서는 `examples/`의 실제 TypeScript 코드로 각 패턴을 보여준다.

## Value Object — `Money`

불변(immutable), 값 기반 동등성, 자기 자신을 검증하는 생성자를 가진다.

```typescript
// account/domain/money.ts
export class Money {
  public readonly amount: number
  public readonly currency: string

  constructor(params: { amount: number; currency: string }) {
    if (params.amount < 0) throw new Error(ErrorMessage['금액은 0 이상이어야 합니다.'])
    this.amount = params.amount
    this.currency = params.currency
  }

  public add(other: Money): Money {
    this.assertSameCurrency(other)
    return new Money({ amount: this.amount + other.amount, currency: this.currency })
  }

  public equals(other: Money): boolean {
    return this.amount === other.amount && this.currency === other.currency
  }

  private assertSameCurrency(other: Money): void {
    if (this.currency !== other.currency) throw new Error(ErrorMessage['통화가 일치하지 않습니다.'])
  }
}
```

- **연산은 새 인스턴스를 반환**한다 (`add`, `subtract`는 `this`를 변경하지 않는다).
- **생성자에서 불변식을 검증**한다 (음수 금액 금지).
- **동등성은 값으로 비교**한다 (`equals`), 참조 동일성이 아니다.

## Aggregate Root — `Account`

`Account`는 이 도메인의 Aggregate Root다. 잔액(`Money`)과 거래 내역(`Transaction[]`)을 캡슐화하고, 상태를 바꾸는 모든 연산은 도메인 이벤트를 함께 기록한다.

```typescript
// account/domain/account.ts
export class Account {
  public readonly accountId: string
  public readonly ownerId: string
  public readonly email: string
  public readonly createdAt: Date
  private _balance: Money
  private _status: AccountStatus
  private readonly _events: AccountDomainEvent[] = []
  private readonly _transactions: Transaction[] = []

  constructor(params: { accountId?: string; ownerId: string; email: string; balance: Money; status: AccountStatus; createdAt?: Date }) {
    this.accountId = params.accountId ?? generateId()
    this.ownerId = params.ownerId
    this.email = params.email
    this._balance = params.balance
    this._status = params.status
    this.createdAt = params.createdAt ?? new Date()
  }

  get balance(): Money { return this._balance }
  get status(): AccountStatus { return this._status }
  get domainEvents(): AccountDomainEvent[] { return [...this._events] }

  public static create(params: { ownerId: string; email: string; currency: string }): Account {
    const account = new Account({ ownerId: params.ownerId, email: params.email, balance: new Money({ amount: 0, currency: params.currency }), status: AccountStatus.ACTIVE })
    account._events.push(new AccountCreated({ accountId: account.accountId, ownerId: account.ownerId, email: account.email, currency: params.currency, createdAt: account.createdAt }))
    return account
  }

  public deposit(amount: Money): Transaction {
    if (this._status !== AccountStatus.ACTIVE) throw new Error(ErrorMessage['활성 상태의 계좌만 입금할 수 있습니다.'])
    if (amount.amount <= 0) throw new Error(ErrorMessage['금액은 0보다 커야 합니다.'])

    this._balance = this._balance.add(amount)
    const transaction = new Transaction({ accountId: this.accountId, type: 'DEPOSIT', amount })
    this._transactions.push(transaction)
    this._events.push(new MoneyDeposited({ accountId: this.accountId, email: this.email, transactionId: transaction.transactionId, amount, balanceAfter: this._balance, createdAt: transaction.createdAt }))
    return transaction
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

핵심 규칙:

- **정적 팩토리 메서드(`Account.create`)로 생성**한다. `new Account(...)`를 직접 호출하는 곳은 생성자 내부와 Repository 구현체(DB에서 복원할 때)뿐이다.
- **`_balance`, `_status`는 private, getter로만 노출**한다. 외부에서 직접 대입할 수 없다 — 반드시 `deposit()`/`withdraw()`/`suspend()` 같은 도메인 메서드를 통해서만 변경된다.
- **모든 상태 변경 메서드가 불변식을 먼저 검증**한다 (`활성 상태의 계좌만 입금할 수 있습니다`, `금액은 0보다 커야 합니다`) — 위반 시 domain/error-message enum을 참조하는 plain `Error`를 던진다 ([error-handling.md](error-handling.md) 참조).
- **상태 변경과 동시에 Domain Event를 `_events`에 push**한다 — Repository가 저장할 때 이 이벤트들을 함께 Outbox에 기록한다 ([domain-events.md](domain-events.md) 참조).
- **`domainEvents` getter는 배열의 복사본을 반환**한다(`[...this._events]`) — 외부에서 내부 배열을 직접 조작할 수 없게 한다.

## Domain Event — `MoneyDeposited`

도메인 이벤트도 값 객체다. 발생 시점의 사실을 불변으로 기록한다.

```typescript
// account/domain/money-deposited.ts
export class MoneyDeposited {
  public readonly accountId: string
  public readonly email: string
  public readonly transactionId: string
  public readonly amount: Money
  public readonly balanceAfter: Money
  public readonly createdAt: Date

  constructor(params: { accountId: string; email: string; transactionId: string; amount: Money; balanceAfter: Money; createdAt: Date }) {
    this.accountId = params.accountId
    this.email = params.email
    this.transactionId = params.transactionId
    this.amount = params.amount
    this.balanceAfter = params.balanceAfter
    this.createdAt = params.createdAt
  }
}
```

- 클래스명은 과거형 동사로 짓는다 (`MoneyDeposited`, `AccountCreated`, `AccountClosed`).
- 이벤트를 소비하는 쪽(알림 발송 등)이 필요로 하는 필드를 전부 담는다 — 이벤트 핸들러가 별도로 Aggregate를 다시 조회하지 않아도 되도록.

## Entity — `Transaction`

`Transaction`은 `Account` Aggregate에 속하는 하위 Entity다. Value Object와 달리 자기 식별자(`transactionId`)를 가지지만, Aggregate Root를 거치지 않고는 독립적으로 저장/조회되지 않는다.

## 관련 문서

- [layer-architecture.md](layer-architecture.md) — Domain 레이어가 프레임워크에 의존하지 않아야 하는 이유
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 저장/조회
- [domain-events.md](domain-events.md) — Aggregate가 수집한 이벤트를 Outbox로 저장하는 흐름
- [aggregate-id.md](aggregate-id.md) — `accountId`/`transactionId` 생성 규칙
