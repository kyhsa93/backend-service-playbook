# The CQRS Pattern

CQRS (Command Query Responsibility Segregation) is a pattern that **separates the responsibilities of writing (Command) and reading (Query)**.
It keeps the same principles as the base architecture (the Domain layer is independent, an Aggregate encapsulates business rules, the Repository pattern).

> Splitting an Application Service into a Command Service / Query Service in the base architecture ([layer-architecture.md](layer-architecture.md)) is already a lightweight application of CQRS. This doc describes **Handler-based CQRS** — the pattern of introducing a Command Bus / Query Bus and splitting each use case into an independent Handler class.

---

### When to adopt it

| Situation | Recommendation |
|---|---|
| There are many use cases and the Service class is getting bloated | Adopt Handler-based CQRS |
| The write and read models need to be fully separate stores | Adopt Handler-based CQRS |
| There are few use cases and the Service class stays simple | The base architecture (splitting the Service) is enough |

---

### Directory structure

```
src/
  <domain>/
    domain/                              # unchanged
      <aggregate-root>.ts
      <domain-event>.ts
      <aggregate>-repository.ts
    application/
      command/
        <verb>-<noun>-command.ts          # a Command object (the input)
        <verb>-<noun>-command-handler.ts  # a CommandHandler (the write logic)
      query/
        <domain>-query.ts                 # the Query interface (abstract class) — unchanged
        <verb>-<noun>-query.ts            # a Query object (the input)
        <verb>-<noun>-query-handler.ts    # a QueryHandler (the read logic)
        <verb>-<noun>-result.ts           # the result object
      event/
        <domain-event>-handler.ts         # an EventHandler (follow-up event processing)
    interface/
      <domain>-controller.ts             # calls the CommandBus / QueryBus
```

---

### Command and CommandHandler

A Command is an **immutable data object** representing a write request. A CommandHandler processes it.

```typescript
// application/command/cancel-order-command.ts
export class CancelOrderCommand {
  public readonly orderId: string
  public readonly reason: string

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}
```

```typescript
// application/command/cancel-order-command-handler.ts
export class CancelOrderCommandHandler {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelOrderCommand): Promise<void> {
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

---

### Query and QueryHandler

A Query is a data object representing a read request. A QueryHandler processes it via a **read-only model** (the Query interface).

```typescript
// application/query/get-orders-query.ts
export class GetOrdersQuery {
  public readonly take: number
  public readonly page: number
  public readonly status?: string[]
}
```

```typescript
// application/query/get-orders-query-handler.ts
export class GetOrdersQueryHandler {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async execute(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

A QueryHandler uses `OrderQuery` (a read-only interface), not `OrderRepository` (the write model). It queries the DB directly, with no Aggregate reconstitution.

---

### The Interface layer — calling the Bus

Instead of a Service, the Controller routes the request to the right Handler through the **CommandBus / QueryBus**.

```typescript
// interface/<domain>-controller.ts (conceptual)
public async cancelOrder(param: CancelOrderRequestParam): Promise<void> {
  return commandBus.execute(new CancelOrderCommand(param))
    .catch((error) => { throw convertToHttpError(error) })
}

public async getOrders(query: GetOrdersRequestQuerystring): Promise<GetOrdersResponseBody> {
  return queryBus.execute(new GetOrdersQuery(query))
    .catch((error) => { throw convertToHttpError(error) })
}
```

---

### EventHandler

A Domain Event doesn't use an in-process event bus. It's delivered via the **Outbox → message queue → EventConsumer** path.

```typescript
// application/event/order-cancelled-handler.ts
export class OrderCancelledHandler {
  public async handle(event: { orderId: string; reason: string }): Promise<void> {
    // follow-up processing (logging, notifications, publishing an Integration Event, etc.)
  }
}
```

→ See [domain-events.md](domain-events.md) for details on publishing/receiving events

---

### The read model (the Query interface)

A QueryHandler looks things up through a **read-only model**, not the Aggregate.

```typescript
// application/query/order-query.ts — the Query interface (abstract class)
export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(query: GetOrderQuery): Promise<GetOrderResult>
}

// infrastructure/order-query-impl.ts — the implementation (direct DB access)
export class OrderQueryImpl extends OrderQuery {
  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    // a query optimized for reading, with no Aggregate reconstitution
  }
}
```

DI binding:

```
OrderQuery (abstract)  ←  OrderQueryImpl (the implementation)
```

---

### Compared with the base architecture

| | Base architecture | Handler-based CQRS |
|---|---|---|
| Write entry point | A CommandService method | CommandHandler.execute() |
| Read entry point | A QueryService method | QueryHandler.execute() |
| Routing | Calling the Service directly | CommandBus / QueryBus |
| Use-case unit | A Service method | A Handler class |
| Read/write separation | Splitting the Service class | A Handler + a separate read model |
| Fits at | Simple to medium scale | Medium to complex scale |

Both approaches keep Domain-layer independence, Aggregate encapsulation, and the Repository pattern exactly the same.

---

### Related docs

- [layer-architecture.md](layer-architecture.md) — the base architecture (splitting the Service)
- [domain-events.md](domain-events.md) — EventHandler and the Outbox pattern
- [repository-pattern.md](repository-pattern.md) — the Repository pattern
