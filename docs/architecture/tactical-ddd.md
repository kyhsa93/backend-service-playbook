# Tactical Design — Aggregate, Entity, Value Object, Domain Event

Once strategic design (BC boundaries, the Context Map) is settled, design the inside of each BC.

---

### Aggregate Root

An Aggregate Root is an object that **encapsulates business rules and invariants**. Nothing outside it can change an Aggregate's internal state directly. A state change must always go through one of the Aggregate Root's domain methods.

**Core principles:**
- The transaction boundary is set at the Aggregate Root level
- Another Aggregate is referenced only by ID (never by object reference)
- On a business-invariant violation, throw an Error immediately, inside the domain method

```typescript
export class Order {
  public readonly orderId: string
  public readonly userId: string
  public readonly items: OrderItem[]
  private _status: 'pending' | 'paid' | 'cancelled'
  private readonly _events: OrderDomainEvent[] = []

  constructor(params: { orderId: string; userId: string; items: OrderItem[]; status: 'pending' | 'paid' | 'cancelled' }) {
    if (params.items.length === 0) throw new Error('An order must have at least one item.')
    this.orderId = params.orderId
    this.userId = params.userId
    this.items = params.items
    this._status = params.status
  }

  get status() { return this._status }
  get domainEvents() { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error('This order has already been cancelled.')
    if (this._status === 'paid') throw new Error('A paid order cannot be cancelled.')
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

> An Application Service never carries out business logic itself. It delegates to an Aggregate method.

---

### Entity

An Entity is an object whose equality is judged by a **unique identifier**. Two objects with the same identifier are the same object even if their other attributes differ. It has a lifecycle (created → modified → deleted).

```typescript
export class OrderItem {
  public readonly itemId: string
  public readonly name: string
  public readonly price: number
  public readonly quantity: number

  constructor(params: { itemId: string; name: string; price: number; quantity: number }) {
    if (params.price <= 0) throw new Error('The item price must be greater than 0.')
    if (params.quantity <= 0) throw new Error('The quantity must be greater than 0.')
    Object.assign(this, params)
  }

  equals(other: OrderItem): boolean {
    return this.itemId === other.itemId
  }
}
```

A child Entity inside an Aggregate Root is only ever accessed and modified through the Aggregate Root.

---

### Value Object

A Value Object is an immutable object whose equality is judged by **the combination of its values**. It has no identifier. Two Value Objects are the same object if all their attributes are equal.

```typescript
export class Money {
  public readonly amount: number
  public readonly currency: 'KRW' | 'USD'

  constructor(amount: number, currency: 'KRW' | 'USD') {
    if (amount < 0) throw new Error('The amount must be 0 or greater.')
    this.amount = amount
    this.currency = currency
  }

  equals(other: Money): boolean {
    return this.amount === other.amount && this.currency === other.currency
  }

  add(other: Money): Money {
    if (this.currency !== other.currency) throw new Error('The currencies are different.')
    return new Money(this.amount + other.amount, this.currency)
  }
}
```

**When to use a Value Object:**
- When its attributes alone convey its meaning, and it doesn't need an identifier
- When immutability needs to be guaranteed (an amount, an address, coordinates, etc.)

---

### Domain Event

A Domain Event is a data class representing **an important state change that happened inside an Aggregate**.
Use a past-tense name (`OrderCancelled`, `UserRegistered`).

```typescript
export class OrderCancelled {
  public readonly orderId: string
  public readonly reason: string
  public readonly cancelledAt: Date

  constructor(params: { orderId: string; reason: string; cancelledAt: Date }) {
    Object.assign(this, params)
  }
}
```

**Domain Event vs. Integration Event:**

| | Domain Event | Integration Event |
|---|---|---|
| Scope | Within the same BC | A published contract between BCs |
| Who creates it | An Aggregate's domain method | An Application EventHandler (converts it after receiving the Domain Event) |
| Schema stability | Free to change internally | Must have an explicit version (`order.cancelled.v1`), and keep backward compatibility |
| Coupling | Only affects inside the BC | An external BC's consumer depends on it |

→ See [domain-events.md](domain-events.md) for detailed publish/receive patterns

---

### Criteria for deciding Aggregate boundaries

Use the criteria below to judge which objects belong in the same Aggregate.

**Group into the same Aggregate when:**
- Objects are created together and deleted together (they share a lifecycle)
- Objects must always change together to keep an invariant intact
- Example: `Order` and `OrderItem` — an order with no items isn't a valid order

**Split into a separate Aggregate when:**
- Objects are looked up and modified independently
- A change on one side doesn't affect the other side's invariants
- Objects that are referenced infrequently and are large
- Example: `Order` and `User` — cancelling an order doesn't affect the user's info

**Signs an Aggregate has grown too large:**
- A single save method changes dozens of rows
- It directly contains another Aggregate as an object
- Transaction conflicts (optimistic-lock failures) happen frequently

> When the boundary isn't clear, **start small**. Merging two Aggregates later is easier than splitting one apart.

---

### Summary of design principles

| Principle | Detail |
|---|---|
| Business rules live in the Aggregate | The Application Service only coordinates, delegating to domain methods |
| The transaction boundary = the Aggregate boundary | Only one Aggregate changes per transaction |
| Reference another Aggregate by ID | An object reference creates coupling — keep only the ID |
| Domain/Application are framework-independent | Pure business logic. Never use a framework decorator — ORM annotations (`@Entity`, `@Column`, etc.) are forbidden too, with no exception. No implementation gets an exception just because "it's the convention in this ecosystem" — persistence mapping must always be split out into a separate Entity/Mapper in the Infrastructure layer |
| Error messages are typed | No free-form strings — define them as an enum (see [error-handling.md](error-handling.md)) |

---

### Related docs

- [strategic-ddd.md](strategic-ddd.md) — identifying BC boundaries, the Context Map
- [layer-architecture.md](layer-architecture.md) — layer roles, the dependency direction
- [repository-pattern.md](repository-pattern.md) — a Repository per Aggregate
- [domain-events.md](domain-events.md) — publishing/receiving a Domain Event
