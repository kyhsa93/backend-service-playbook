# Aggregate Creation and ID Handling

Every Aggregate's ID uses a string in **UUID v4 (hyphens removed)** format. The ID is assigned directly in the Aggregate's constructor.

### ID Generation Rules

- **Format**: a 32-character hex string with the `-` removed from a UUID v4
- **Where it's generated**: the Aggregate's constructor (the Domain layer)
- **Type**: `string`

```typescript
// Correct
'550e8400e29b41d4a716446655440000'   // 32 characters, no hyphens

// Incorrect
'550e8400-e29b-41d4-a716-446655440000'  // includes hyphens
1, 2, 3                                  // auto-increment numbers
```

### The ID Generation Utility

```typescript
// common/generate-id.ts
import { randomUUID } from 'crypto'

export function generateId(): string {
  return randomUUID().replace(/-/g, '')
}
```

If `randomUUID()` is returned as-is without stripping the hyphens, `harness/evaluators/rules/aggregate-id.evaluator.ts` catches it as `aggregate-id.generate-id-raw-uuid`.

### Usage in the Aggregate

```typescript
// domain/order.ts
import { generateId } from '@/common/generate-id'

export class Order {
  public readonly orderId: string
  // ...

  constructor(params: {
    orderId?: string
    userId: string
    items: OrderItem[]
    status: 'pending' | 'paid' | 'cancelled'
  }) {
    this.orderId = params.orderId ?? generateId()
    // ...
  }
}
```

- On new creation: omitting `orderId` lets the constructor assign it automatically
- On restoring from the DB: pass through the existing `orderId` as-is

### TypeORM Entity

```typescript
// infrastructure/entity/order.entity.ts
import { BaseEntity } from '@/database/base.entity'

@Entity('order')
export class OrderEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  orderId: string

  @Column({ type: 'char', length: 32 })
  userId: string

  @Column()
  status: string

  @OneToMany(() => OrderItemEntity, (item) => item.order, { cascade: true })
  items: OrderItemEntity[]
}
```

### Repository Implementation

```typescript
// infrastructure/order-repository-impl.ts — reuses the Aggregate's ID as-is when saving
public async saveOrder(order: Order): Promise<void> {
  const manager = this.transactionManager.getManager()
  await manager.save(OrderEntity, {
    orderId: order.orderId,
    userId: order.userId,
    status: order.status,
    items: order.items.map((i) => ({
      itemId: i.itemId,
      name: i.name,
      price: i.price,
      quantity: i.quantity
    }))
  })
  // see domain-events.md for saving the domain events to the outbox
}
```
