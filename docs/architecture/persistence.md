# Persistence Patterns — Transactions, Common Entity Columns, Soft Delete, Migrations

These are the common rules a Repository implementation ([repository-pattern.md](repository-pattern.md)) follows when it actually handles data. The concrete syntax of the ORM/query builder differs per language/framework, but the patterns themselves apply the same way everywhere.

---

## Transaction propagation — Unit of Work

Wrap a write operation that spans multiple Repositories in a single transaction. Instead of explicitly passing a transaction object into every call, propagate it implicitly using **context-local storage** (per language: Node's `AsyncLocalStorage`, Go's `context.Context`, Java/Kotlin's `ThreadLocal`, Python's `contextvars`, etc.).

### TransactionManager (Infrastructure layer)

```typescript
// database/transaction-manager — conceptual
class TransactionManager {
  private readonly storage = createContextLocalStorage<TransactionClient>()

  // runs the callback within a transaction
  async run<T>(fn: () => Promise<T>): Promise<T> {
    return this.dataSource.transaction((client) =>
      this.storage.run(client, fn)
    )
  }

  // returns the tx client if a transaction context exists, otherwise the default client
  getClient(): TransactionClient {
    return this.storage.getStore() ?? this.dataSource.defaultClient
  }
}
```

### Using it in a Repository implementation

A Repository implementation automatically picks up the current transaction context via `transactionManager.getClient()`. If it's called outside a transaction, it just uses the default client, so no separate branching is needed.

```typescript
class OrderRepositoryImpl implements OrderRepository {
  constructor(private readonly transactionManager: TransactionManager) {}

  async saveOrder(order: Order): Promise<void> {
    const client = this.transactionManager.getClient()
    await client.save(OrderTable, { ... })
  }
}
```

### Wrapping multiple Repositories together

```typescript
async cancelOrder(command: CancelOrderCommand): Promise<void> {
  const order = await this.orderRepository
    .findOrders({ orderId: command.orderId, take: 1, page: 0 })
    .then((r) => r.orders.pop())
  if (!order) throw new Error(ErrorMessage['Order not found.'])

  order.cancel(command.reason)

  await this.transactionManager.run(async () => {
    await this.paymentRepository.deletePaymentMethods(order.orderId)
    await this.orderRepository.saveOrder(order)   // saving to the outbox happens inside this too
  })
}
```

When calling just one Repository, there's no need to wrap it in `run()` — call it directly. Even if a Repository implementation touches multiple tables internally, `getClient()` reuses whatever transaction context already exists.

---

## Common Entity columns — createdAt, updatedAt, deletedAt

Every table includes `createdAt`, `updatedAt`, and `deletedAt` columns. Define the common columns in a base class/mixin so every Entity inherits and reuses them.

```typescript
// database/base-entity — conceptual
abstract class BaseEntity {
  createdAt: Date
  updatedAt: Date
  deletedAt: Date | null
}
```

---

## Soft delete

When deleting data, the default is a soft delete — recording a timestamp in `deletedAt` — rather than an actual (hard) delete.

```typescript
// correct — soft delete
async deleteOrder(orderId: string): Promise<void> {
  const client = this.transactionManager.getClient()
  await client.softDelete(OrderTable, { orderId })
}

// wrong — hard delete
async deleteOrder(orderId: string): Promise<void> {
  const client = this.transactionManager.getClient()
  await client.delete(OrderTable, { orderId })   // actually deletes it — do not use
}
```

- **A `deletedAt IS NULL` condition must apply by default on lookups** (either use the ORM's soft-delete feature, or add the condition to the query explicitly).
- Only include soft-deleted data explicitly, via a separate option (`withDeleted`, etc.), when it's actually needed.
- If child entities also need to be soft-deleted together, handle it explicitly and in order inside the Repository implementation (delete children first, accounting for FK constraints on the parent table, etc.).

---

## Migrations

Manage schema changes through migration files. Automatic schema sync (`synchronize`/`ddl-auto: update`) is **for development environments only** — production must always use migrations (leaving auto-sync on in production can cause unintended schema changes on deploy).

```
migrations/
  20240401120000_create_order.sql
  20240402090000_add_order_status.sql
```

```bash
# Generate a migration — either detect schema changes or write it manually
<migration-tool> generate create_order

# Run migrations
<migration-tool> migrate up

# Roll back a migration (the last one)
<migration-tool> migrate down
```

### Principles

- **Always generate a migration after a schema change**: auto-sync is only for local development.
- **Migration files are committed**: even auto-generated ones are reviewed, then committed.
- **Write migrations that can be rolled back**: implement both up/down (or an equivalent symmetric pair of operations).
- **Keep data migrations separate from schema changes**: don't put a schema change and a data transformation in the same migration file.

---

## Summary of principles

- **Propagate transactions implicitly via context-local storage.** Never pass a transaction object explicitly into every call.
- **Every table has createdAt/updatedAt/deletedAt.**
- **Deletion defaults to soft delete.** Only use a hard delete explicitly, in an exceptional case.
- **Manage schema changes through migrations.** Auto-sync is local-only.

### Related docs

- [repository-pattern.md](repository-pattern.md) — separating the Repository interface from its implementation
- [domain-events.md](domain-events.md) — saving to the outbox in the same transaction
