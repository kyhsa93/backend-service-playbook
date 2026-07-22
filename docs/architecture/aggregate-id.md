# Aggregate ID Generation

### Principles

- **Where the ID is generated**: the Domain layer (the Aggregate constructor)
- **Who generates it**: the server (never use a client-supplied ID)
- **Type**: `string`
- **Format**: a UUID v4 with hyphens stripped, a 32-char hex string

```
'550e8400e29b41d4a716446655440000'   // correct — 32 chars, no hyphens
'550e8400-e29b-41d4-a716-446655440000'  // wrong — has hyphens
1, 2, 3                                  // wrong — an auto-increment number
```

**Why not use an auto-increment numeric ID:**
- It exposes the record count/creation order externally (a security concern)
- It can collide across multiple services/shards
- The ID isn't determined until it's created in the DB, so it can't be pre-generated in the Domain layer

---

### ID generation utility

```typescript
// common/generate-id.ts
import { randomUUID } from 'crypto'

export function generateId(): string {
  return randomUUID().replace(/-/g, '')
}
```

---

### Using it in an Aggregate

```typescript
// domain/order.ts
export class Order {
  public readonly orderId: string

  constructor(params: {
    orderId?: string   // omit on new creation, passed in on DB restoration
    userId: string
    items: OrderItem[]
    status: 'pending' | 'paid' | 'cancelled'
  }) {
    this.orderId = params.orderId ?? generateId()
    // ...
  }
}
```

- **New creation**: omitting `orderId` has the constructor assign it automatically
- **DB restoration**: the Repository implementation passes the existing `orderId` through as-is

---

### Handling the ID in a Repository implementation

The Repository uses the Aggregate's ID as-is. It never issues a fresh ID from the DB.

```typescript
// infrastructure/order-repository-impl.ts (conceptual)
public async saveOrder(order: Order): Promise<void> {
  await persist({
    orderId: order.orderId,   // use the ID the Aggregate already has
    userId: order.userId,
    status: order.status,
    // ...
  })
}
```

---

### IDs for child Entities

A child Entity inside an Aggregate (like OrderItem) uses the same UUID-v4-based string ID. That said, where a numeric auto-increment fits better (a simple ordering index, etc.), decide based on the domain's characteristics.

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — the Aggregate constructor pattern
- [repository-pattern.md](repository-pattern.md) — saving an Aggregate in a Repository
