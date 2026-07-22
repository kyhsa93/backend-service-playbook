# Cross-Domain Call Patterns

Communication between BCs comes in two forms: **synchronous (Adapter/ACL)** and **asynchronous (Integration Event)**. The selection criteria follow the root [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md). This document explains, from a NestJS perspective, how those two patterns are implemented as **actually-working code** in `examples/`.

`examples/` has two Bounded Contexts.

- **Account BC** (`src/account/`) — opening accounts, deposits/withdrawals, suspension, closure. The upstream (Supplier).
- **Card BC** (`src/card/`) — card issuance. Downstream (Customer), dependent on the account. It has a **Customer-Supplier** relationship with Account.

Card depends on Account in two directions.

| Direction | Need | Pattern | Implementation location |
|------|------|------|----------|
| **Immediately checking** whether the linked account is active when issuing a card | Needed for the response | Synchronous **Adapter (ACL)** | `card/application/adapter/`, `card/infrastructure/account-adapter-impl.ts` |
| The card is also **suspended/closed** when the account is suspended/closed | Eventual consistency acceptable | Asynchronous **Integration Event** | Account publishes → Outbox → received by `card/interface/integration-event/` |

Account **never imports Card at all** (the dependency direction is one-way, Card → Account). For asynchronous delivery, the Outbox and `EventHandlerRegistry` loosely connect the two BCs.

---

## 1. Synchronous — the Adapter pattern (ACL)

Card issuance is a synchronous lookup because the response to "does the linked account exist and is it active?" is needed to process the current request.

### Principles

1. **From the Application (Command Handler), call the external domain only through an Adapter interface.** Never directly inject the external domain's Repository/domain object.
2. **Define the Adapter interface on the calling side (`card/application/adapter/`)** as an abstract class.
3. **Place the Adapter implementation on the calling side (`card/infrastructure/`)**, injecting and calling the read service (`AccountQuery`) that the external domain module `exports`.
4. **The ACL translates the upstream model.** Instead of exposing Account's `AccountStatus` enum as-is, it converts it into the minimal shape Card needs (`{ accountId, active }`), and translates an "account not found" error into `null`.

### Actual code

```typescript
// card/application/adapter/account-adapter.ts — the interface (abstract class)
export abstract class AccountAdapter {
  abstract findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean } | null>
}
```

```typescript
// card/infrastructure/account-adapter-impl.ts — the implementation (ACL)
@Injectable()
export class AccountAdapterImpl extends AccountAdapter {
  constructor(private readonly accountQuery: AccountQuery) { super() }  // the read service exported by AccountModule

  public async findAccount(query: { accountId: string; ownerId: string }) {
    try {
      const account = await this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.ownerId })
      return { accountId: account.accountId, active: account.status === AccountStatus.ACTIVE }  // translate the upstream model
    } catch (error) {
      if (error instanceof Error && error.message === AccountErrorMessage['Account not found.']) return null
      throw error
    }
  }
}
```

```typescript
// card/application/command/issue-card-command-handler.ts — synchronous lookup through the Adapter
const account = await this.accountAdapter.findAccount({ accountId: command.accountId, ownerId: command.requesterId })
if (!account) throw new Error(ErrorMessage['The account to link could not be found.'])
if (!account.active) throw new Error(ErrorMessage['Only an active account can have a card issued.'])

const card = Card.issue({ accountId: command.accountId, ownerId: command.requesterId, brand: command.brand })
await this.transactionManager.run(async () => { await this.cardRepository.saveCard(card) })
```

`AccountModule` exposes **only the read service** via `exports: [AccountQuery]`. It never exposes the Repository or domain objects. `CardModule` injects it via `imports: [AccountModule]` and wires it into `AccountAdapterImpl`.

> **Note**: never call the external BC's **write methods** through an Adapter. If a write is needed, switch to an Integration Event (see section 2 below).

---

## 2. Asynchronous — Integration Event

When an account is suspended/closed, its cards must also be suspended/closed, but since this is a **state change** where eventual consistency is acceptable, the two BCs aren't bound into a single transaction. Account publishes an Integration Event, and Card reacts independently.

### Overall flow (based on actual code)

```
[Account] suspend command
  → Account.suspend() collects the AccountSuspended Domain Event
  → Repository.saveAccount() writes it to the Outbox (eventType="AccountSuspended")
  → the Command Handler returns immediately after saving (no synchronous drain)
  → OutboxPoller (@Interval(1000)) picks up the Outbox row and publishes it to SQS
  → OutboxConsumer (long polling) receives it from SQS → EventHandlerRegistry.handle('AccountSuspended')
       → AccountSuspendedHandler (application/event/)
         transforms it into AccountSuspendedIntegrationEventV1 and writes it to the Outbox
         (eventType="account.suspended.v1")
  → this new Outbox row also goes through the same Poller/Consumer path again, round-tripping through SQS →
    EventHandlerRegistry.handle('account.suspended.v1')
       → CardIntegrationEventController.onAccountSuspended (interface/integration-event/)
       → SuspendCardsByAccountCommand → turns ACTIVE cards into SUSPENDED
```

Key files:

| Role | File |
|------|------|
| Integration Event definition (public contract) | `account/application/integration-event/account-suspended-integration-event.ts` (`account.suspended.v1`) |
| Domain Event → Integration Event conversion | `account/application/event/account-suspended-handler.ts` (`@HandleEvent`) |
| Outbox draining (DB→SQS) + routing (SQS→Handler) | `outbox/outbox-poller.ts` + `outbox/outbox-consumer.ts` + `outbox/event-handler-registry.ts` (shared by every domain, no per-domain relay) |
| External BC receiving end | `card/interface/integration-event/card-integration-event-controller.ts` (`@HandleIntegrationEvent`) |
| Reacting use case | `card/application/command/suspend-cards-by-account-command-handler.ts` |

### Defining the Integration Event — a versioned public contract

```typescript
// account/application/integration-event/account-suspended-integration-event.ts
export class AccountSuspendedIntegrationEventV1 {
  public readonly eventName = 'account.suspended.v1' as const  // used as the Outbox row's eventType
  constructor(public readonly accountId: string, public readonly suspendedAt: string) {}
}
```

This is the **external public contract**, separate from the internal `AccountSuspended` Domain Event (whose schema can change freely). `OutboxWriter` writes `eventType` as the `eventName` if present (otherwise the class name).

### Conversion happens only in the Application EventHandler

```typescript
// account/application/event/account-suspended-handler.ts
@HandleEvent('AccountSuspended')
public async handle(event: { accountId: string; email: string; suspendedAt: string }): Promise<void> {
  await this.outboxWriter.saveAll([
    new AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt ?? new Date().toISOString())
  ])
  // ...follow-up processing within the same BC, such as notifications
}
```

The Aggregate never creates an Integration Event directly. The EventHandler in `application/event/` is the **only conversion point**, and the one exception allowed to use `OutboxWriter` directly in the Application layer (the Command Handler is prohibited from doing so).

### Receiving happens in the Interface Integration Event Controller

```typescript
// card/interface/integration-event/card-integration-event-controller.ts
@Injectable()
export class CardIntegrationEventController {
  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('account.suspended.v1')
  public async onAccountSuspended(event: { accountId: string }): Promise<void> {
    await this.commandBus.execute(new SuspendCardsByAccountCommand({ accountId: event.accountId }))
  }
}
```

This is an **Interface input adapter**, the same as the HTTP Controller · Task Controller. It calls only its own BC's Command, and throws exceptions as-is so the OutboxConsumer doesn't delete the message and retries.

### Routing — why the publishing BC doesn't import the receiving BC

This repo's Outbox has no per-domain Relay — a single `outbox/` module shared by every BC drains asynchronously through SQS (see [domain-events.md](domain-events.md)). `OutboxPoller` carries an Outbox row to SQS as-is regardless of eventType, and `OutboxConsumer` **delegates the eventType it received from SQS to `EventHandlerRegistry`** — every event, whether Account's or Card's, passes through this single registry.

```typescript
// outbox/outbox-consumer.ts (the key part)
const eventType = message.MessageAttributes?.eventType?.StringValue
await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
```

The publishing BC (Account) registers its own Domain Event handlers in its own module's `onModuleInit`, and the receiving BC (Card) registers its own Integration Event receiving end the same way.

```typescript
// card/card-module.ts
onModuleInit(): void {
  this.registry.register('account.suspended.v1', (p) => this.cardIntegrationEventController.onAccountSuspended(p as never))
  this.registry.register('account.closed.v1', (p) => this.cardIntegrationEventController.onAccountClosed(p as never))
}
```

This way, **Account delivers events to Card without importing Card**. The only point of contact between publisher and receiver is the versioned event name (`account.suspended.v1`).

The Integration Event row (`account.suspended.v1`) that `AccountSuspendedHandler` converts and newly writes doesn't continue immediately within the same command processing — it's picked up again on `OutboxPoller`'s next tick (up to 1 second later) and goes through the same Poller→SQS→Consumer path once more.

### Idempotency

Since Integration Events assume at-least-once delivery, the receiving side's use case must be **idempotent**. `SuspendCardsByAccountCommandHandler` only selects `ACTIVE` cards to suspend, so even if the same event is re-received (with the card already suspended), nothing happens.

---

## Mapping onto Context Map patterns

| Context Map pattern | This repo's implementation |
|----------------|------------------|
| ACL (Anticorruption Layer) | `AccountAdapter` + `AccountAdapterImpl` — translates the upstream `AccountStatus`/errors |
| Customer-Supplier | Card (downstream) queries via the Adapter + reacts via Integration Event |
| OHS/PL (Published Language) | Versioned as `account.suspended.v1` / `account.closed.v1` |

## Related Documents

- [../../../../docs/architecture/cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — criteria for choosing sync vs async (framework-agnostic)
- [domain-events.md](domain-events.md) — details of publishing/receiving Outbox·Integration Events
- [module-pattern.md](module-pattern.md) — dependencies between modules, `exports`
