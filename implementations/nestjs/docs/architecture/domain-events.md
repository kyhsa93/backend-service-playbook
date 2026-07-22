# Domain Event Publishing Pattern

> **The actual path used in this repo**: this implements exactly the only path prescribed by the root [domain-events.md](../../../../docs/architecture/domain-events.md) — **Outbox writes happen inside the `Repository.save()` transaction, queue publishing is done by an independently-scheduled `OutboxPoller`, and EventHandler execution is done by an `OutboxConsumer` that long-polls SQS**. There is no path in this repo where the Command Handler synchronously drains events in the same process right after saving. `src/outbox/outbox-poller.ts`/`outbox-consumer.ts` are the actual code, and `EventHandlerRegistry` (`outbox/event-handler-registry.ts`) is a `Map<eventType, handler[]>`-based registry that each domain module's `onModuleInit()` populates by calling `register()` — the `ModuleRef`-based dynamic routing shown in some examples below is not used.

### Conceptual distinction — Domain Event vs Integration Event

**Domain Event**: an event internal to the same Bounded Context. The result of an internal Aggregate state change. Its structure can change freely and isn't coupled to external BCs.
- Created: inside an Aggregate domain method via `_events.push(new OrderCancelled(...))`
- Saved: written to the Outbox by the Repository
- Received: by `application/event/<domain-event>-handler.ts` (`@HandleEvent`) in the same BC — called when the OutboxConsumer receives this event from SQS

**Integration Event**: a **public contract** with external BCs and external systems. The name and schema must be stable, and versioned explicitly (`order.cancelled.v1`). The only point consuming sides can depend on.
- Created: after an **Application EventHandler** receives a Domain Event, it's transformed as needed and written to the Outbox (the Aggregate never creates it directly)
- Received: an Integration Event published by an external BC is **external input** from this BC's point of view, so it's received in `interface/integration-event/<domain>-integration-event-controller.ts` (an Interface input adapter, same as the HTTP Controller / Task Controller)

Without this distinction, coupling between BCs grows, and refactoring an internal event breaks external consumers.

### Overall flow

```
[1. Execute domain logic]
  Command Handler → calls an Aggregate domain method → the Aggregate collects Domain Event objects internally

[2. Save — a single transaction]
  Inside Repository.save(aggregate):
    - saves the Aggregate state
    - saves aggregate.domainEvents to the outbox table
    - aggregate.clearEvents()
  Transaction commits → the Aggregate and the events are confirmed together, or rolled back together
  The Command Handler ends here — it returns without calling anything like processPending().

[3. OutboxPoller — runs independently every 1 second, sends Outbox → SQS]
  Reads rows with processed=false from the outbox table
    → sends each row to SQS (eventType as MessageAttributes, payload as MessageBody)
    → marks a successfully sent row as processed=true immediately
      (processed now means "delivery to SQS is done," not "the handler finished processing" —
       from here on, retry/at-least-once guarantees are handled by SQS's visibility timeout + DLQ, not the outbox)

[4. OutboxConsumer — independently long-polls SQS, calls the EventHandler]
  Waits to receive via ReceiveMessageCommand(WaitTimeSeconds)
    → on receiving a message, looks up the handler in EventHandlerRegistry by eventType(MessageAttributes) and calls it
    → handler succeeds → deletes (acks) via DeleteMessageCommand
    → handler fails (or no handler registered) → doesn't delete → re-received (retried) after the visibility timeout

[5. (Optional) Publishing an Integration Event — transformed by the Application EventHandler]
  When the EventHandler needs to notify an external BC about a Domain Event:
    → constructs an IntegrationEventV1 object
    → writes an outbox row for the external BC via OutboxWriter (in the same transaction)
    → delivered to the external BC through the same path as steps 3-4 (OutboxPoller → SQS → OutboxConsumer)

[6. Receiving an external BC's Integration Event — the Interface Integration Event Controller]
  When an Integration Event published by another BC arrives at this BC:
    → interface/integration-event/<domain>-integration-event-controller.ts
    → received by an @HandleIntegrationEvent('order.cancelled.v1') method
    → calls the Command Handler (CommandBus) to run this domain's own use case
```

**The "drain synchronously in the same process right after saving" approach is not used.** If the Command Handler committed the save transaction and then immediately called OutboxPoller/OutboxConsumer to process the event right there, the "write" and "event processing" that the Outbox pattern was originally meant to decouple would be bound back together into a single request. `harness/evaluators/rules/domain-event-outbox.evaluator.ts` flags it as `domain-event-outbox.command-handler.forbidden-sync-drain` whenever a Command Handler (`-command-handler.ts`) directly references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` or calls something like `processPending()`.

### Step 1: Collecting events in the Aggregate

When an Aggregate's domain method changes state, it adds an event object to its internal `_events` array.

```typescript
// domain/order.ts
export class Order {
  private readonly _events: OrderDomainEvent[] = []

  get domainEvents(): OrderDomainEvent[] { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error(...)
    this._status = 'cancelled'
    // create the event object inside the domain method
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

### Step 2: Saving the Aggregate + Outbox transactionally in the Repository

Inside **the Repository implementation's save method**, bind saving the Aggregate and saving the outbox into a single transaction. The Command Handler never touches the outbox directly.

```typescript
// infrastructure/order-repository-impl.ts
@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async saveOrder(order: Order): Promise<void> {
    const manager = this.transactionManager.getManager()
    // saving the Aggregate + saving the event outbox is the same transaction
    await manager.save(OrderEntity, {
      orderId: order.orderId,
      userId: order.userId,
      status: order.status,
      items: order.items.map((i) => ({ ... }))
    })
    // save to the outbox if there are domain events
    if (order.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  }
}
```

The Command Handler just needs to call Repository.save() — once the save is done, it returns as-is:

```typescript
// application/command/cancel-order-command-handler.ts
@CommandHandler(CancelOrderCommand)
export class CancelOrderCommandHandler implements ICommandHandler<CancelOrderCommand> {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository.findOrders({ ... }).then((r) => r.orders.pop())
    if (!order) throw new Error(...)

    order.cancel(command.reason)                   // domain method → collects the event

    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)   // saves the Aggregate + outbox together
    })
    // it ends here — publishing Outbox → SQS and running the EventHandler
    // are handled independently by OutboxPoller/OutboxConsumer.
  }
}
```

### Outbox Entity — actual code

```typescript
// outbox/outbox.entity.ts
import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('outbox')
export class OutboxEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  eventId: string

  @Column()
  eventType: string

  @Column('text')
  payload: string

  @Column({ default: false })
  processed: boolean   // becomes true once OutboxPoller finishes publishing to SQS. Whether the
                        // handler actually finished processing is known not by this column but by SQS (ack/redelivery).

  @CreateDateColumn()
  createdAt: Date
}
```

### OutboxWriter — actual code

Saves an event to the outbox table inside a transaction. Only called from a Repository implementation, or from an `application/event/` EventHandler (when publishing an Integration Event) — referencing it from a Command Handler or any other Application subdirectory is flagged as `domain-event-outbox.command-service.outbox-writer-injection`.

```typescript
// outbox/outbox-writer.ts
@Injectable()
export class OutboxWriter {
  constructor(private readonly transactionManager: TransactionManager) {}

  public async saveAll(events: object[]): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.insert(OutboxEntity, events.map((event) => ({
      eventId: generateId(),
      eventType: (event as { eventName?: string }).eventName ?? event.constructor.name,
      payload: JSON.stringify(event),
      processed: false
    })))
  }
}
```

### Step 3: OutboxPoller — sends Outbox → SQS (actual code)

`src/outbox/outbox-poller.ts` — runs on its own every 1 second via `@nestjs/schedule`'s `@Interval(1000)`. It removes the old structure where Account/Payment each had their own separate Relay, and this single Poller drains the entire outbox table (all domains) — aligning it with the "single shared outbox" convention already used by Go/Java/Kotlin/FastAPI.

```typescript
// outbox/outbox-poller.ts
@Injectable()
export class OutboxPoller {
  private readonly logger = new Logger(OutboxPoller.name)
  private isPolling = false   // don't run overlapping if the previous tick hasn't finished

  constructor(
    private readonly transactionManager: TransactionManager,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  @Interval(1000)
  public async poll(): Promise<void> {
    if (this.isPolling) return
    this.isPolling = true
    try {
      await this.drainOnce()
    } catch (error) {
      this.logger.error({ message: 'Outbox polling failed', error })
    } finally {
      this.isPolling = false
    }
  }

  private async drainOnce(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.find(OutboxEntity, {
      where: { processed: false }, order: { createdAt: 'ASC' }, take: 100
    })
    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: getDomainEventQueueUrl(),
          MessageBody: row.payload,
          MessageAttributes: { eventType: { DataType: 'String', StringValue: row.eventType } }
        }))
        await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
      } catch (error) {
        this.logger.error({ message: 'Failed to publish to SQS', event_type: row.eventType, event_id: row.eventId, error })
      }
    }
  }
}
```

**Choice of polling interval**: 1 second. `@nestjs/schedule`'s `@Interval` maps more directly to this class's purpose of "keep running at a fixed interval" than `@Cron` does. Preventing overlapping runs (the `isPolling` flag) keeps it safe even when processing takes longer than 1 second.

### Step 4: OutboxConsumer — receives SQS → EventHandler (actual code)

`src/outbox/outbox-consumer.ts` — `OnModuleInit` starts a background loop exactly once at bootstrap (it isn't recreated per request). It long-polls via `ReceiveMessageCommand`'s `WaitTimeSeconds`.

```typescript
// outbox/outbox-consumer.ts
@Injectable()
export class OutboxConsumer implements OnModuleInit, OnModuleDestroy {
  private running = false

  constructor(
    private readonly registry: EventHandlerRegistry,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  public onModuleInit(): void {
    this.running = true
    void this.pollLoop()
  }

  public onModuleDestroy(): void {
    this.running = false   // stops the loop during Graceful Shutdown
  }

  private async pollLoop(): Promise<void> {
    const queueUrl = getDomainEventQueueUrl()
    while (this.running) {
      const result = await this.sqs.send(new ReceiveMessageCommand({
        QueueUrl: queueUrl, MaxNumberOfMessages: 10,
        MessageAttributeNames: ['eventType'], WaitTimeSeconds: 5
      }))
      for (const message of result.Messages ?? []) {
        await this.handleMessage(queueUrl, message)
      }
    }
  }

  private async handleMessage(queueUrl: string, message: Message): Promise<void> {
    const eventType = message.MessageAttributes?.eventType?.StringValue
    try {
      if (!eventType) throw new Error('The eventType message attribute is missing.')
      await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
      await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
    } catch (error) {
      this.logger.error({ message: 'Failed to process event', event_type: eventType, error })
      // don't delete — it gets re-received and retried after the visibility timeout.
    }
  }
}
```

### EventHandlerRegistry — handler routing (actual code)

A `Map`-based registry that maps `eventType` strings to handler functions. The `@HandleEvent`/`@HandleIntegrationEvent` decorators are just positional markers — the actual routing is configured via `register()`, called explicitly from each domain module's `onModuleInit()` (dynamic lookup based on `ModuleRef` isn't used).

```typescript
// outbox/event-handler-registry.ts
type EventHandlerFn = (payload: object) => Promise<void>

@Injectable()
export class EventHandlerRegistry {
  private readonly handlers = new Map<string, EventHandlerFn[]>()

  public register(eventType: string, handler: EventHandlerFn): void {
    const list = this.handlers.get(eventType) ?? []
    list.push(handler)
    this.handlers.set(eventType, list)
  }

  public async handle(eventType: string, payload: object): Promise<void> {
    for (const handler of this.handlers.get(eventType) ?? []) {
      await handler(payload)
    }
  }
}
```

Each domain module registers its own Domain Event Handlers, plus its receiving side for Integration Events published by other BCs, into this single registry.

```typescript
// account/account-module.ts — actual code (excerpt)
export class AccountModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly accountIntegrationEventController: AccountIntegrationEventController,
    private readonly accountCreatedHandler: AccountCreatedHandler,
    // ...remaining Domain Event Handlers
  ) {}

  onModuleInit(): void {
    this.registry.register('AccountCreated', (payload) => this.accountCreatedHandler.handle(payload as never))
    // ...remaining Domain Event registrations
    this.registry.register('payment.completed.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCompleted(payload as never))
    // ...remaining Integration Event registrations
  }
}
```

### EventHandler — receiving and processing a domain event

```typescript
// application/event/order-cancelled-handler.ts
@Injectable()
export class OrderCancelledHandler {
  private readonly logger = new Logger(OrderCancelledHandler.name)

  @HandleEvent('OrderCancelled')
  public async handle(event: { orderId: string; reason: string }): Promise<void> {
    this.logger.log({ message: 'Order cancellation event received', order_id: event.orderId })
    // follow-up processing: request a refund, send a notification, restock, etc.
  }
}
```

### Directory structure — actual code

```
src/
  outbox/
    outbox-module.ts                 ← OutboxModule (@Global)
    outbox.entity.ts                 ← the Outbox table Entity
    outbox-writer.ts                 ← saves an event inside a transaction (called from a Repository / Application EventHandler)
    outbox-poller.ts                 ← sends Outbox → SQS (@Interval(1000))
    outbox-consumer.ts                ← routes SQS → EventHandlerRegistry (long polling)
    sqs-client-provider.ts           ← creates the SQSClient (same setup as the SES/Secrets Manager client)
    event-handler-registry.ts        ← eventType → Handler routing (@HandleEvent · @HandleIntegrationEvent decorators + Map)
  <domain>/
    domain/
      <domain-event>.ts              ← Domain Event definition (internal use, no versioning)
    application/
      event/
        <domain-event>-handler.ts    ← Domain EventHandler (@HandleEvent)
      integration-event/
        <name>-integration-event.ts  ← Integration Event definition (public external contract, versioned V1 etc.)
    interface/
      integration-event/
        <domain>-integration-event-controller.ts  ← receives an external BC's Integration Event (@HandleIntegrationEvent)
```

### Module registration — actual code

```typescript
// outbox/outbox-module.ts
@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter, EventHandlerRegistry, SqsClientProvider, OutboxPoller, OutboxConsumer],
  exports: [TransactionManager, OutboxWriter, EventHandlerRegistry]
})
export class OutboxModule {}
```

`OutboxPoller`/`OutboxConsumer` are only in `providers`, not in `exports` — other modules must not be able to inject and call them directly. Since they're providers of a `@Global()` module, they're created once as singletons at app bootstrap, and `OutboxConsumer.onModuleInit()` starts the background loop at that point — `AppModule` must import `ScheduleModule.forRoot()` for `@Interval` to work.

```typescript
// app-module.ts (excerpt)
@Module({
  imports: [
    ScheduleModule.forRoot(),   // enables OutboxPoller's @Interval
    // ...
    OutboxModule,
    AccountModule,
    CardModule,
    PaymentModule
  ]
})
export class AppModule {}
```

### SQS client — actual code

Follows the existing SES/Secrets Manager client setup as-is (the `AWS_ENDPOINT_URL` branch, static test credentials).

```typescript
// outbox/sqs-client-provider.ts
export const SQS_CLIENT = Symbol('SQS_CLIENT')

export function createSqsClient(): SQSClient {
  return new SQSClient({
    region: getAwsRegion(),
    endpoint: getAwsEndpoint(),
    credentials: getAwsCredentials()
  })
}

export const SqsClientProvider: FactoryProvider<SQSClient> = {
  provide: SQS_CLIENT,
  useFactory: createSqsClient
}
```

The queue URL is read by `config/aws.config.ts`'s `getDomainEventQueueUrl()` from the `SQS_DOMAIN_EVENT_QUEUE_URL` environment variable.

### LocalStack + Docker Compose — actual code

```yaml
# docker-compose.yml (excerpt)
localstack:
  environment:
    SERVICES: ses,secretsmanager,sqs
```

```bash
# localstack/init-sqs.sh — create the DLQ first, then attach it to the main queue via RedrivePolicy
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url .../domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

```env
# .env.development — add the SQS queue URL
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

DLQ + `maxReceiveCount=3` follows exactly the convention required by [scheduling.md — DLQ Monitoring](../../../../docs/architecture/scheduling.md#dlq-monitoring).

### Event handler idempotency

SQS guarantees at-least-once delivery. Since the same message can be **received more than once**, every EventHandler must be implemented **idempotently**.

> **Note**: the Task Queue's `@TaskConsumer` operates under the same at-least-once assumption, and it provides the same 3-tier model — **framework-level ledger (the `idempotencyKey` option)** · **Level 3 strong atomicity**, etc. This is a shared pattern across both EventHandlers and Task Controllers; see [scheduling.md — Idempotency](./scheduling.md#idempotency) for the detailed structure. For EventHandlers with significant side effects too (re-charging, calling an external API, etc.), applying the same ledger strategy is recommended. This repo's `DepositByPaymentCommandHandler`/`WithdrawByPaymentCommandHandler` actually use a `referenceId`-based Level 2 Ledger (`hasTransactionWithReference`).

```typescript
// Correct — an idempotent handler
@HandleEvent('OrderCancelled')
public async handle(event: { orderId: string }): Promise<void> {
  const refund = await this.refundRepository
    .findRefunds({ orderId: event.orderId, take: 1, page: 0 })
    .then((r) => r.refunds.pop())
  if (refund) return  // already processed — prevents duplicate execution
  await this.refundRepository.saveRefund(new Refund({ orderId: event.orderId, ... }))
}

// Incorrect — a non-idempotent handler
@HandleEvent('OrderCancelled')
public async handle(event: { orderId: string }): Promise<void> {
  await this.refundRepository.saveRefund(new Refund({ orderId: event.orderId, ... }))
}
```

### Defining an Integration Event — a public contract for external BCs

An Integration Event is defined in **application/integration-event/**. Since it's a public contract separated from the Domain Event, its name carries a **version suffix (V1, etc.)** and the schema is deliberately designed to be flat (internal Aggregate structure must never be exposed).

```typescript
// order/application/integration-event/order-cancelled-integration-event.ts
export class OrderCancelledIntegrationEventV1 {
  public readonly eventName = 'order.cancelled.v1' as const
  constructor(
    public readonly orderId: string,
    public readonly cancelledAt: string,
    public readonly reason: string
  ) {}
}
```

File/class naming:
- File: `<name>-integration-event.ts` (application/integration-event/)
- Class: `<Name>IntegrationEventV1`; add `V2` on a schema change (V1 continues being published alongside it during the compatibility period)
- eventName literal: `<domain>.<verb-past>.v<N>` — used as SQS's `MessageAttributes.eventType`

### Publishing an Integration Event — the Application EventHandler

When an EventHandler that received a Domain Event in the same BC needs to notify an external BC, it constructs an Integration Event and writes it to the Outbox. **The EventHandler is the one exception allowed to use OutboxWriter directly in the Application layer** (the Command Handler is still prohibited from doing so).

```typescript
// order/application/event/order-cancelled-handler.ts
@Injectable()
export class OrderCancelledHandler {
  constructor(private readonly outboxWriter: OutboxWriter) {}

  @HandleEvent('OrderCancelled')
  public async handle(event: { orderId: string; reason: string; cancelledAt: string }): Promise<void> {
    // perform any follow-up processing within the same BC here (calling a Command Handler, etc.)

    // when notifying an external BC, transform it into an Integration Event and publish
    await this.outboxWriter.saveAll([
      new OrderCancelledIntegrationEventV1(event.orderId, event.cancelledAt, event.reason)
    ])
  }
}
```

> Never pass the Domain Event itself to the outside as-is. An internal schema change would break external consumers. The EventHandler is the transformation point.

### Receiving an Integration Event — the Interface Integration Event Controller

When an Integration Event published by an external BC arrives at this BC, it's received by the **Interface layer's Integration Event Controller**. Treated as the same kind of input adapter as the HTTP Controller / Task Controller.

```typescript
// card/interface/integration-event/card-integration-event-controller.ts — actual code
@Injectable()
export class CardIntegrationEventController {
  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('account.suspended.v1')
  public async onAccountSuspended(event: { accountId: string }): Promise<void> {
    await this.commandBus.execute(new SuspendCardsByAccountCommand({ accountId: event.accountId }))
  }
}
```

File/class naming:
- File: `<domain>-integration-event-controller.ts` (interface/integration-event/)
- Class: `<Domain>IntegrationEventController`
- Decorator: `@HandleIntegrationEvent('<event-name>.v<N>')` — distinct from `@HandleEvent` for Domain Events

Just like the Task Controller, exceptions are thrown as-is so the OutboxConsumer doesn't delete the message and instead handles retry (→ DLQ). The Controller never uses `generateErrorResponse`.

### Principles

- **A Domain Event is created only inside the Aggregate**: a domain method adds the event object to `_events`.
- **The Aggregate never creates an Integration Event directly**: the Application EventHandler transforms a Domain Event and writes it to the Outbox.
- **`Repository.save()` saves the Aggregate + Outbox together**: the Command Handler never touches the outbox directly.
- **The Command Handler returns immediately after saving**: it never calls OutboxRelay/OutboxPoller/OutboxConsumer directly — the harness catches synchronous draining as `domain-event-outbox.command-handler.forbidden-sync-drain`.
- **Delivery follows the order Outbox → SQS → Handler**: OutboxPoller (publishing) and OutboxConsumer (receiving/executing) each run independently on their own schedule.
- **Handlers must be implemented idempotently**: to handle SQS's at-least-once delivery characteristic.
- **Domain Event Handlers live in application/event/**: they specify the eventType via the `@HandleEvent` decorator, and each domain module registers them via `EventHandlerRegistry.register()` in its `onModuleInit()`.
- **Integration Event Controllers live in interface/integration-event/**: they specify a versioned event name via `@HandleIntegrationEvent` and call the CommandBus to run only their own BC's use case.
- **Integration Events are versioned**: state the public contract explicitly as `V1`/`order.cancelled.v1`, publishing the old and new versions together during a compatibility period.
