# Scheduling / Batch Jobs

Principles for periodic work and batch processing.

> **Scope of this doc**: covers scheduling's framework-agnostic principles and patterns. You can get started with just the 3 minimum requirements below; the detailed patterns further down are a reference design for cases where scale, multiple instances, and transactional consistency matter.

---

## Minimum requirements

1. **Place the Scheduler in the Infrastructure layer.** Never put it in the Application layer, where the business logic lives.
2. **A Task handler is idempotent.** Since a message queue is at-least-once delivery, the same Task can run twice, and the result must be the same either way.
3. **If you use a message queue, a DLQ is the default.** It stops infinite retries and isolates a poison message.

---

## Separating the Scheduler's role

A Scheduler **never runs business logic directly**. All it does is enqueue a Task onto the queue. The actual run happens when a Task Consumer receives the message from the queue and calls a Command Service.

```
[Scheduler] --(enqueue)--> [task_outbox] --(Relay)--> [message queue] --(Consumer)--> [TaskController] --(calls)--> [CommandService]
```

**Why**:
- **Safe with multiple instances**: even if several instances run the same Cron at the same moment, the FIFO queue's deduplication means only one gets processed.
- **Retries built in**: on a Consumer failure, it's automatically received again once the visibility timeout passes → goes to the DLQ once `maxReceiveCount` is exceeded.
- **Backpressure**: even if the workload spikes, it just piles up in the queue and gets consumed at the Consumer's processing rate.
- **Observability**: track batch status via queue metrics (message count, processing lag, DLQ count).

```typescript
// infrastructure/<concern>-scheduler.ts
class OrderCleanupScheduler {
  constructor(private readonly taskQueue: TaskQueue) {}

  // the Cron handler only enqueues
  async enqueueDailyCleanup(): Promise<void> {
    const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
    await this.taskQueue.enqueue(
      'order.cleanup-expired',
      {},
      { groupId: 'order.cleanup', deduplicationId: dedupId }
    )
  }
}
```

---

## The Task Outbox pattern

A DB change and enqueuing a Task in a Command Service **must be bundled atomically**. Calling SendMessage directly on the message queue creates a dual-write problem — the DB commits but the message send fails, or the message sends but the DB rolls back, leaving an inconsistency.

For the same reason as a Domain Event's Outbox pattern, **a Task also follows the path: write to the `task_outbox` table → the Relay publishes it**.

```
Inside the Command transaction:
  the DB change + inserting a task_outbox row
  → the transaction commits
  → TaskOutboxRelay polls it and publishes to the message queue
  → a Task Consumer receives it and runs it
```

```typescript
// An Application Service — the DB change and enqueuing the Task happen in the same transaction
await transactionManager.run(async () => {
  await orderRepository.saveOrder(order)
  await taskQueue.enqueue(
    'order.archive',
    { orderId: order.orderId },
    { groupId: order.orderId, deduplicationId: `order.archive-${order.orderId}` }
  )
})
```

Use the same path even when calling this from somewhere with no transaction context, like a Scheduler (Cron). It's a single row insert, so it's naturally atomic, and having one unified path keeps operations simple.

---

## The Task Controller — the Interface layer

A Task Controller is an **input adapter in the Interface layer**. Just as an HTTP Controller receives an HTTP request and delegates to an Application Service, a Task Controller receives a message-queue message and calls a CommandService.

```
HTTP Controller   ← an HTTP request    → CommandService
Task Controller   ← a message queue    → CommandService
```

**Principles:**
- **Delegate to a Command with no logic**: a Task Controller only calls a CommandService's method. Never put conditional branching or business rules in it.
- **Throw the error as-is**: never use the HTTP Controller's catch-plus-error-conversion pattern. The Consumer catches the exception and hands it off to retry/DLQ. Swallowing the exception loses the failure.
- **No direct DB access**: only inject the CommandService. Delegate idempotency-ledger handling to the framework.

```typescript
// interface/<domain>-task-controller.ts
class OrderTaskController {
  constructor(private readonly orderCommandService: OrderCommandService) {}

  async cleanupExpired(): Promise<void> {
    await this.orderCommandService.cleanupExpiredOrders()
  }

  async archive(payload: ArchiveOrderCommand): Promise<void> {
    await this.orderCommandService.archiveOrder(payload)  // the exception propagates as-is
  }
}
```

---

## The MessageGroupId strategy

In a FIFO queue, **messages with the same MessageGroupId are processed in order**. The GroupId is the **boundary of parallelism**.

| Situation | groupId setting |
|------|-------------|
| A global Cron batch (once a day, etc.) | The Task category: `'order.cleanup'` |
| Needs sequential processing per Aggregate | The Aggregate ID: `orderId` |
| Order doesn't matter + high throughput | A random UUID, or `taskType + random` |

The same group runs serially; different groups run in parallel. Put **only the minimum level of ordering you actually need** into the groupId.

---

## Idempotency

A message queue guarantees **at-least-once delivery**. The same Task can run more than once. A Task handler must always be implemented idempotently.

The 3-level strategy follows the same model as [domain-events.md — Event-handler idempotency](domain-events.md#event-handler-idempotency):

| Level | Situation | Implementation |
|------|------|----------|
| Level 1 — inherently idempotent | Produces the same result even if run repeatedly | No extra mechanism needed |
| Level 2 — a ledger | A handler with side effects | Record that it was processed in the DB, skip on a duplicate |
| Level 3 — strong atomicity | Needs "record only if it succeeded" | Wrap the handler logic and the ledger in the same transaction |

```typescript
// Level 1 — inherently idempotent: handled based on the expired state, so it's the same result no matter how many times it runs
async cleanupExpiredOrders(): Promise<void> {
  const { orders } = await orderRepository.findOrders({ status: ['expired'], take: 100, page: 0 })
  for (const order of orders) {
    order.archive()  // internally a no-op if it's already archived
    await orderRepository.saveOrder(order)
  }
}
```

---

## Cron safety with multiple instances

Even if several instances run a Cron at the same moment, a **date-based deduplicationId** prevents a duplicate enqueue.

```typescript
// a day-granularity dedupId — only one gets processed within the FIFO 5-minute dedup window
const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
await taskQueue.enqueue('order.cleanup-expired', {}, { groupId: 'order.cleanup', deduplicationId: dedupId })
```

Even if multiple instances enqueue on the same day, the `deduplicationId` is identical, so only one ends up in the FIFO queue.

**Watch out**: many scheduling libraries automatically swallow an exception thrown in a Cron handler. Without an **explicit try-catch + logging**, a failure becomes unobservable.

```typescript
async enqueueDailyCleanup(): Promise<void> {
  try {
    await this.taskQueue.enqueue(/* ... */)
  } catch (error) {
    logger.error({ message: 'Cron enqueue failed', error })
    // no need to rethrow — it'll be retried on the next Cron tick
  }
}
```

---

## Monitoring the DLQ

Messages piling up in the DLQ are **evidence of a code bug or a poison payload**.

- Moves to the DLQ once `maxReceiveCount` is exceeded
- Set an alarm for DLQ message count > 0
- After fixing the root cause, redrive the DLQ back into the original queue

---

## Payload size limits

SQS caps a single message at 256KB.

- **Put only small metadata in the payload**: something like `{ orderId: 'o1' }`.
- **Offload large data to S3** and put only the key in the payload: `{ orderId: 'o1', payloadS3Key: 'tasks/abc.json' }`.

---

## Summary of principles

- **The Scheduler is in the Infrastructure layer**: never use a scheduling decorator in Application/Domain.
- **The Scheduler only enqueues**: the Cron handler only calls `TaskQueue.enqueue`. Never run business logic directly.
- **The Task Controller is in the Interface layer**: the same kind of input adapter as an HTTP Controller. It throws errors as-is.
- **Enqueuing goes through the Outbox**: guarantees atomicity between the DB change and enqueuing the Task. Blocks the dual-write problem.
- **A Command is idempotent**: since delivery is at-least-once, running it repeatedly must produce the same result.
- **A DLQ is required**: set a DLQ on every Task queue and watch it with an alarm.
- **Log a Cron exception explicitly**: since the scheduling framework often swallows the exception, log it yourself.

---

### Related docs

- [domain-events.md](domain-events.md) — Task Queue vs. Domain Event, the 3-level idempotency strategy
- [layer-architecture.md](layer-architecture.md) — transaction propagation (AsyncLocalStorage)
- [graceful-shutdown.md](graceful-shutdown.md) — graceful shutdown for a Consumer
