# Repository Pattern

### One Repository per Aggregate Root

- **1 Aggregate Root = 1 Repository interface + 1 Repository implementation**
- Place the interface (an abstract class) in the `domain/` layer, and the implementation in the `infrastructure/` layer.
- A child Entity inside an Aggregate is saved/loaded together, through the Aggregate Root's Repository.

```
src/
  order/
    domain/
      order-repository.ts          ← abstract class (the interface)
    infrastructure/
      order-repository-impl.ts     ← extends OrderRepository (the implementation)
```

### DI binding — using an abstract class as the token

An Application Service is injected with the Repository typed as the abstract class. The implementation is bound in the Infrastructure layer via the DI container.

```typescript
// Application Service — injected typed as the abstract class
constructor(private readonly orderRepository: OrderRepository) {}

// Infrastructure — binding the implementation (the mechanism differs per framework)
// OrderRepository → OrderRepositoryImpl
```

→ See `docs/implementations/` for how DI wiring works per framework

### Repository method-naming rules

| Purpose | Method-name pattern | Example |
|------|--------------|------|
| List lookup | `find<Noun>s` | `findOrders`, `findUsers` |
| Save/upsert | `save<Noun>` | `saveOrder`, `saveUser` |
| Delete | `delete<Noun>` | `deleteOrder`, `deleteUser` |

- **Lookup is always the single `find<Noun>s`** — use the list-lookup method whether it's a single record or a list
- For a single-record lookup, the Service calls it with `take: 1` and uses the `.then(r => r.<noun>s.pop())` pattern
- **A Repository must not have an update method** — look it up, modify it via the Aggregate's domain method, and save it via `save<Noun>`

### Common columns — createdAt, updatedAt, deletedAt

Every Entity has common columns recording when it was created/modified/deleted.

```typescript
// common columns (a framework-agnostic concept)
createdAt : datetime  — creation time (set automatically)
updatedAt : datetime  — last-modified time (updated automatically)
deletedAt : datetime | null  — deletion time (null means not deleted)
```

These three columns are put in a common BaseEntity abstract class for other Entities to inherit. See `docs/implementations/` for the per-framework implementation.

---

### Soft delete

When deleting data, **never actually remove the row (a hard delete)**. Use a soft delete that records a timestamp in `deletedAt`.

**Why:**
- Enables history tracking and auditing
- Lets you recover from an accidental deletion
- Handles deletion safely with no referential-integrity errors

```typescript
// A Repository implementation — soft delete
public async deleteOrder(orderId: string): Promise<void> {
  await db.softDelete(OrderEntity, { orderId })  // deletedAt = now()
}

// A lookup automatically excludes deleted data (deletedAt IS NULL)
public async findOrders(query: FindOrdersQuery): Promise<{ orders: Order[]; count: number }> {
  // most ORMs apply the deletedAt IS NULL condition automatically
}

// When you need to look up data including deleted rows
public async findOrdersIncludingDeleted(orderId: string): Promise<Order | undefined> {
  // use whatever the ORM offers, e.g. a withDeleted option
}
```

A child entity must **also be soft-deleted together**. This is handled inside the Repository implementation; the Service calls `delete<Noun>` just once:

```typescript
public async deleteOrder(orderId: string): Promise<void> {
  await db.softDelete(OrderItemEntity, { orderId })  // child entity first
  await db.softDelete(OrderEntity, { orderId })       // then the Aggregate Root
}
```

---

### The dynamic filter pattern

When a lookup condition is optional, build a dynamic `where` clause that only adds a condition when its value is present.

```typescript
// A Repository implementation — conditional where-clause chaining
public async findOrders(query: {
  orderId?: string
  userId?: string
  status?: string[]
  take: number
  page: number
}): Promise<{ orders: Order[]; count: number }> {
  const conditions: Record<string, unknown> = {}

  if (query.orderId) conditions.orderId = query.orderId
  if (query.userId)  conditions.userId  = query.userId
  if (query.status?.length) conditions.status = query.status  // an IN condition

  // the result is always { orders: Order[]; count: number }
}
```

**Principles:**
- Only apply a condition when its value is present (an `if (query.field)` guard)
- For an array condition, also exclude an empty array (`[]`) from being applied (`if (arr?.length)`)
- With no conditions, it's a full lookup — always apply pagination via `take`/`page`

---

### Domain boundaries — bidirectional access via a mapping table

The boundary between two domains is defined by a **mapping table**.
A mapping table must be lookup-able/savable/deletable from **both connected domains' Repository implementations**.
Each Repository implementation accesses the mapping table using **its own domain's identifier**.

```
user ──── userGroupMap ──── group ──── groupRoleMap ──── role
   user-side identifier: userId          group-side identifier: groupId
   group-side identifier: groupId         role-side identifier: roleId
```

### Cascading saves/deletes in a Repository

When `save<Noun>` / `delete<Noun>` is called, a Repository implementation internally **handles the connected mapping table together with the child entities**.
The Service never manages the cascade order directly — it only calls a single domain-level method.

```typescript
// inside infrastructure/group-repository-impl.ts
public async deleteGroup(groupId: string): Promise<void> {
  // FK reference order: mapping tables first → then the main entity
  await deleteGroupRoleMap(groupId)
  await deleteUserGroupMap(groupId)
  await deleteGroup(groupId)
}
```

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — details on Aggregate Root design
- [layer-architecture.md](layer-architecture.md) — the layer dependency direction
- [domain-events.md](domain-events.md) — saving a Domain Event → the Outbox from a Repository
- [persistence.md](persistence.md) — transaction propagation, soft delete, migrations
