# Tactical Design — Aggregate, Entity, Value Object (NestJS)

> For the concept definitions and boundary criteria, see the root [tactical-ddd.md](../../../../docs/architecture/tactical-ddd.md). This document shows each pattern using `examples/`'s actual TypeScript code.

## Value Object — `Money`

Immutable, value-based equality, with a constructor that validates itself.

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

- **An operation returns a new instance** (`add`, `subtract` never mutate `this`).
- **Invariants are validated in the constructor** (a negative amount is prohibited).
- **Equality is compared by value** (`equals`), not by reference identity.

## Aggregate Root — `Account`

`Account` is this domain's Aggregate Root. It encapsulates the balance (`Money`) and the transaction history (`Transaction[]`), and every operation that changes state also records a domain event.

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

Key rules:

- **Created via a static factory method (`Account.create`)**. The only places that call `new Account(...)` directly are inside the constructor itself and the Repository implementation (when reconstructing from the DB).
- **`_balance` and `_status` are private, exposed only via getters**. They can't be assigned to directly from the outside — they're changed only through domain methods like `deposit()`/`withdraw()`/`suspend()`.
- **Every state-changing method validates its invariants first** ("only an active account can accept a deposit", "the amount must be greater than 0") — on violation, it throws a plain `Error` referencing the domain/error-message enum (see [error-handling.md](error-handling.md)).
- **Every state change simultaneously pushes a Domain Event to `_events`** — the Repository records these events into the Outbox together when saving (see [domain-events.md](domain-events.md)).
- **The `domainEvents` getter returns a copy of the array** (`[...this._events]`) — preventing the outside from directly manipulating the internal array.

**Other Aggregates may only be referenced by ID (object references are prohibited)** — this applies equally whether it's a different Aggregate within the same BC or crossing a BC boundary. `harness/evaluators/rules/no-cross-aggregate-reference.evaluator.ts` catches violations within the same BC (Payment·Refund in `payment/domain/`), and `harness/evaluators/rules/no-cross-bc-domain-import.evaluator.ts` catches `src/<bc>/domain/*.ts` directly importing another BC's `domain/*` (`no-cross-bc-domain-import.cross-bc-domain-import`).

## Domain Event — `MoneyDeposited`

A domain event is also a value object. It immutably records the fact as of the moment it occurred.

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

- Name the class with a past-tense verb (`MoneyDeposited`, `AccountCreated`, `AccountClosed`).
- Include every field the consuming side (sending a notification, etc.) needs — so the event handler never has to re-fetch the Aggregate separately.

## Entity — `Transaction`

`Transaction` is a child Entity belonging to the `Account` Aggregate. Unlike a Value Object, it has its own identifier (`transactionId`), but it's never saved/retrieved independently without going through the Aggregate Root.

## Related Documents

- [layer-architecture.md](layer-architecture.md) — why the Domain layer must not depend on the framework
- [repository-pattern.md](repository-pattern.md) — saving/retrieving at the Aggregate level
- [domain-events.md](domain-events.md) — the flow of saving the events an Aggregate collected into the Outbox
- [aggregate-id.md](aggregate-id.md) — the generation rule for `accountId`/`transactionId`
