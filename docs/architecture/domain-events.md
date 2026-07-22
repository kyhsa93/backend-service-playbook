# Domain Event Publishing Pattern

### Conceptual distinction — Domain Event vs. Integration Event

**Domain Event**: something that happened inside the same Bounded Context. The result of a state change inside an Aggregate. Its shape can change freely and it's never coupled to an external BC.
- Created: inside an Aggregate's domain method, via `_events.push(new OrderCancelled(...))`
- Saved: loaded into the Outbox together, **inside the same transaction where `Repository.save()` persists the Aggregate** (not via a separate save call)
- Received: by `application/event/<domain-event>-handler.ts` in the same BC — this handler doesn't run the instant the Aggregate creates the event; it runs **later, when a Relay reads the event that was saved to the Outbox and hands it over**

**Integration Event**: a **published contract** with an external BC or external system. Its name/schema must be stable, with an explicit version (`order.cancelled.v1`). The only point a consumer can depend on.
- Created: by the **Application EventHandler**, which converts it (when needed) while processing a Domain Event (delivered from the Outbox), and loads it into the Outbox the same way (the Aggregate never creates it directly)
- Received: an Integration Event published by an external BC follows the same principle — once the event saved in the publisher's Outbox is delivered, the receiving side picks it up in `interface/integration-event/`

Without this distinction, coupling between BCs grows, and refactoring an internal event breaks an external consumer.

---

### The whole flow

There are three key points: **(1) saving to the Outbox happens together, inside the same transaction where `Repository.save()` persists the Aggregate** — no Command Service or any other code ever writes to the outbox separately. **(2) Carrying an event saved in the Outbox onto the message queue is the responsibility of a Poller that runs independently on its own schedule, not the Command Service** — the Command Service ends the request the instant the save finishes, with no knowledge of, or involvement in, when the event gets processed. **(3) An EventHandler only ever runs through a Consumer that receives events directly from the message queue** — the moment an Aggregate creates an event, the moment an event queued in the Outbox goes out to the queue, and the moment a Consumer processes that event are three separate, independent flows, and **there is no exception where they run synchronously back-to-back.**

```
[1. Run domain logic]
  Command Service → calls an Aggregate domain method → the Aggregate collects Domain Event objects internally

[2. Save — a single transaction]
  Inside Repository.save(aggregate):
    - saves the Aggregate's state
    - saves aggregate.domainEvents to the outbox table
    - aggregate.clearEvents()
  The transaction commits → the Aggregate and its events are confirmed together, or rolled back together
  The Command Service ends here — it never calls any later step.

[3. OutboxPoller — runs independently on its own schedule, sends saved events to the queue]
  OutboxPoller: runs on its own separate schedule (e.g. every 1-2 seconds)
    → reads rows with processed=false from the outbox table
    → publishes each row to the message queue (SQS, etc.) (eventType as a message attribute, payload as the body)
    → marks a successfully published row as processed=true immediately
       (here, processed means "delivery to the queue finished," not "a handler finished processing it" —
        delivery guarantees from this point on are the message queue's own redelivery mechanism's job, not the outbox's)

[4. OutboxConsumer — independently waits on the queue, calls the EventHandler]
  OutboxConsumer: polls the message queue on a short cycle (long polling)
    → on receiving a message, calls the right EventHandler under application/event/ based on eventType
    → deletes (acks) the message if the handler succeeds
    → doesn't delete the message if the handler fails — once the queue's visibility timeout
      passes, it's automatically received again and retried (at-least-once, can be isolated to a DLQ)

[5. (Optional) Publishing an Integration Event — converted by the Application EventHandler]
  When an EventHandler needs to notify an external BC of a Domain Event:
    → builds an IntegrationEventV1 object
    → loads it into an outbox row meant for the external BC via OutboxWriter (in the same transaction)
    → it's then delivered to the external BC through the same path as steps 3-4 (Poller → queue → Consumer)

[6. An external BC receives an Integration Event]
  When an Integration Event published by another BC arrives at your own BC:
    → interface/integration-event/<domain>-integration-event-controller.ts
    → a handler receives it → calls a Command Service to run your own domain's use case
```

**Never use the approach of "draining synchronously, right after saving, within the same process."** If the Command Service commits the save transaction and then immediately calls a Relay/Consumer to process the event right there, the "write" and "event processing" that the Outbox pattern was meant to separate get bundled back into a single request — this separation only actually holds if the Poller/Consumer always run independently. Every Domain Event/Integration Event follows this path, with no exceptions.

---

### Step 1: collecting events in the Aggregate

```typescript
export class Order {
  private readonly _events: OrderDomainEvent[] = []

  get domainEvents() { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error('This order has already been cancelled.')
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

---

### Step 2: saving the Aggregate + Outbox together in a transaction, in the Repository

Inside a Repository implementation's save method, wrap saving the Aggregate and saving to the outbox in a single transaction. The Command Service never touches the outbox directly.

```typescript
// infrastructure/order-repository-impl.ts (conceptual)
public async saveOrder(order: Order): Promise<void> {
  await transaction(async () => {
    await persistAggregate(order)
    if (order.domainEvents.length > 0) {
      await outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  })
}
```

---

### The Outbox table schema

```
outbox
  eventId    : string (PK, a unique ID)
  eventType  : string (e.g. 'OrderCancelled', 'order.cancelled.v1')
  payload    : string (JSON-serialized)
  processed  : boolean (default false) — set to true once OutboxPoller finishes publishing it to the message queue.
               Whether a handler has actually finished processing it is known by the message queue (ack/redelivery), not this column.
  createdAt  : datetime
```

This assumes at-least-once delivery. An EventHandler must be implemented idempotently.

---

### Integration Event schema rules

```typescript
// application/integration-event/order-cancelled-integration-event.ts
export class OrderCancelledIntegrationEventV1 {
  public readonly eventName = 'order.cancelled.v1'   // an explicit version
  public readonly orderId: string
  public readonly reason: string
  public readonly cancelledAt: Date
}
```

- Name format: `<domain>.<event>.<version>` (e.g. `order.cancelled.v1`)
- Backward compatibility: add new fields as optional. Bump the version (`v2`) when removing/changing an existing field.
- An Integration Event never exposes a Domain Event to the outside as-is. The Application EventHandler is the conversion point.

---

---

### Task Queue vs. Domain Event

Both are asynchronous processing, but they differ in **purpose and unit of meaning**.

| | Domain Event | Task Queue |
|---|---|---|
| Unit of meaning | A fact (past tense): "X happened" | A command (imperative): "carry out X" |
| Who produces it | The Aggregate (inside a domain method) | A Scheduler / an Application Service |
| Number of handlers | 1:N (one event can have many handler subscribers) | 1:1 (one handler per taskType) |
| Example | `OrderCancelled` → refund, restock, and notify all handled at once | a batch cleaning up expired orders, resending a notification |

**How to decide:** "is this observing the result of running a Command?" → a Domain Event. "I want to run this work asynchronously" → a Task Queue.

---

### Event-handler idempotency

A message queue guarantees **at-least-once delivery**. That means the same event can be delivered more than once. An EventHandler must always be implemented idempotently.

**A 3-level idempotency strategy:**

| Level | Situation | Implementation |
|------|------|----------|
| Level 1 — inherently idempotent | The handler's own logic produces the same result even if it runs repeatedly | No extra mechanism needed |
| Level 2 — a ledger | A handler with side effects (an external API call, processing a refund, etc.) | Record that it was processed in the DB, and skip on a duplicate delivery |
| Level 3 — strong atomicity | When you need "record only if it succeeded" | Wrap the handler logic and the ledger save in the same transaction |

**Level 1 example — inherently idempotent:**

```typescript
// State-based handling: cancel() itself ignores an order that's already cancelled
public async handle(event: { orderId: string; reason: string }): Promise<void> {
  const order = await this.orderRepository.findOrders({ orderId: event.orderId, take: 1, page: 0 })
    .then((r) => r.orders.pop())
  if (!order) return  // ignore if it's already been deleted
  if (order.status === 'cancelled') return  // ignore if it's already been handled
  order.doSomething()
  await this.orderRepository.saveOrder(order)
}
```

**Level 2 example — a ledger:**

```typescript
// records that it was processed, keyed by eventId, and skips if it's already there
public async handle(event: { eventId: string; orderId: string }): Promise<void> {
  const alreadyProcessed = await this.eventLedger.check(event.eventId)
  if (alreadyProcessed) return

  await this.orderCommandService.doSomething({ orderId: event.orderId })
  await this.eventLedger.record(event.eventId)
}
```

**Don't use Level 2 when Level 1 will do.** Running a ledger table has an operating cost.

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — defining a Domain Event
- [cross-domain-communication.md](cross-domain-communication.md) — the criteria for choosing between an Integration Event and an Adapter
- [repository-pattern.md](repository-pattern.md) — saving to the Outbox from a Repository
