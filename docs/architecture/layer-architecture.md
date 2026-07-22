# Layer Architecture

### Dependency direction

```
Interface (Controller)  →  Application (Service)  →  Domain (Aggregate, the Repository interface)
                                                          ↑
                                                   Infrastructure (the Repository implementation)
```

- A higher layer can depend on a lower layer, but a lower layer never depends on a higher layer.
- The Domain layer depends on no layer at all (including the framework and the ORM).
- The Infrastructure layer implements the Domain layer's interfaces (dependency inversion).

> The code examples use TypeScript, but include no framework decorators (`@Injectable`, etc.).
> See `docs/implementations/` for per-framework implementation detail.

---

### The Domain layer

The core of the business rules. Written as **pure code with no dependency on any framework**.

1. **Aggregate Root** — encapsulates business rules and invariants
2. **Entity** — an object with a unique identifier and a lifecycle
3. **Value Object** — an immutable object. Equality is judged by the combination of its attributes
4. **Domain Event** — a data class representing an important event that happened in the domain
5. **The Repository interface** — an abstract class defined per Aggregate Root. The implementation is placed in the Infrastructure layer

```typescript
// domain/order-repository.ts — the Repository interface (abstract class)
export abstract class OrderRepository {
  abstract saveOrder(order: Order): Promise<void>
  abstract findOrders(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
    readonly userId?: string
    readonly status?: string[]
  }): Promise<{ orders: Order[]; count: number }>
  abstract deleteOrder(orderId: string): Promise<void>
}
```

→ See [tactical-ddd.md](tactical-ddd.md) for details on Aggregate, Entity, Value Object, Domain Event

---

### The Application layer — the coordinator

An Application Service is split into a **Command Service** (writes) and a **Query Service** (reads).

#### Command Service

Handles use cases that change data. It never carries out business logic itself — it delegates to the Aggregate.

1. Look up the Aggregate from the Repository
2. Call a domain method on the Aggregate
3. Save the Aggregate via the Repository

```typescript
// application/command/order-command-service.ts
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async cancelOrder(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error('Order not found.')

    order.cancel(command.reason)

    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)
    })
  }
}
```

#### Query Service

Handles use cases that look up data. It never uses the Repository directly — it's injected with the application layer's **Query interface** (an abstract class). The Query implementation is placed in the Infrastructure layer.

```typescript
// application/query/order-query.ts — the Query interface (abstract class)
export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(query: GetOrderQuery): Promise<GetOrderResult>
}
```

```typescript
// application/query/order-query-service.ts
export class OrderQueryService {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

#### Command/Query separation principle

- **The Repository** is used only by the Command Service. It handles lookup/save at the Aggregate level.
- **The Query interface** is used only by the Query Service. It handles read-optimized lookups, with no need to reconstitute an Aggregate.
- In the Interface layer, a write request calls the Command Service, and a read request calls the Query Service.

---

### The Infrastructure layer

1. **The Repository implementation** — implements the Domain layer's abstract class. The only layer that uses the ORM client directly.
2. **The Query implementation** — implements the Application layer's Query abstract class. Writes read-optimized queries directly.
3. **Event publishing** — message-queue integration, event serialization.
4. **External-system adapters** — an Anticorruption Layer. Converts an external API response into the domain model.

```typescript
// infrastructure/order-query-impl.ts — the Query implementation
export class OrderQueryImpl extends OrderQuery {
  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    // queries the DB directly — no need to reconstitute an Aggregate, uses a read-optimized query
  }
}
```

The Infrastructure layer binds the Repository and Query implementations to the Domain/Application interfaces via DI:

```
OrderRepository (abstract)  ←  OrderRepositoryImpl (the implementation)
OrderQuery (abstract)        ←  OrderQueryImpl (the implementation)
```

→ See `docs/implementations/` for how DI wiring works per framework

#### Transaction propagation — the AsyncLocalStorage pattern

When wrapping a write operation across multiple Repositories in a single transaction, this is the pattern for propagating the transaction client **implicitly, with no function argument needed**.

```typescript
// conceptual — TransactionManager
const transactionStorage = new AsyncLocalStorage<TxClient>()

class TransactionManager {
  async run<T>(fn: () => Promise<T>): Promise<T> {
    return db.transaction((txClient) =>
      transactionStorage.run(txClient, fn)  // propagates txClient via ALS inside the callback
    )
  }

  getClient(): TxClient {
    return transactionStorage.getStore() ?? db.defaultClient  // the tx client if it's in the ALS, otherwise the default client
  }
}
```

```typescript
// Application Service
await transactionManager.run(async () => {
  await paymentRepository.deletePaymentMethods(order.orderId)  // runs inside the tx
  await orderRepository.saveOrder(order)                        // the same tx
})

// A Repository implementation (doesn't need to take tx as an argument)
public async saveOrder(order: Order): Promise<void> {
  const client = transactionManager.getClient()  // automatically gets the tx client from the ALS
  await client.save(...)
}
```

**Advantage:** the Application Service never has to pass the transaction client directly into a Repository. The Repository interface stays uncontaminated by the concept of a transaction. Calling it outside a transaction just uses the default client, so it works naturally either way.

---

### The Interface layer

The entry point for external requests (HTTP, a message queue, etc.).

1. Receives the request
2. Calls a Command Service or Query Service
3. Catches an error → converts it into an HTTP/protocol exception

#### An Interface DTO = a thin wrapper around an Application object

An Interface DTO wraps a Query/Result/Command from the Application layer via `extends`. The principle is a thin wrapper with no separate logic or conversion.

```typescript
// interface/dto/get-order-request-param.ts
import { GetOrderQuery } from '@/order/application/query/get-order-query'
export class GetOrderRequestParam extends GetOrderQuery {}

// interface/dto/delete-order-request-param.ts
import { DeleteOrderCommand } from '@/order/application/command/delete-order-command'
export class DeleteOrderRequestParam extends DeleteOrderCommand {}
```

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — details on Aggregate, Entity, Value Object
- [repository-pattern.md](repository-pattern.md) — details on the Repository pattern
- [cqrs-pattern.md](cqrs-pattern.md) — the Command/Query-Bus-based pattern (optional)
- [domain-events.md](domain-events.md) — Domain Event, the Outbox pattern
