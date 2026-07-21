# Communication Patterns Between Bounded Contexts

When calling another BC within the same process, choose one of two approaches: **synchronous (Adapter)** or **asynchronous (Integration Event)**.

### Decision criteria

| Question | Synchronous (Adapter) | Asynchronous (Integration Event) |
|----------|--------------|--------------------------|
| Is the response needed to handle the current request? | Yes | No |
| Does the called BC change state? | No (read-only) | Yes |
| Must the current transaction roll back on failure? | Yes | No (eventual consistency is acceptable) |
| Is the call direction one-way? | Usually can go either way | One-way (publishing an event) |

> If a **state change** — not a read — is needed in an external BC, don't wrap the two BCs in one transaction via a synchronous call. Let each BC process it independently via an Integration Event.

### Pattern-selection flow

```
Does the current request's response need data from an external BC?
  └─ Yes → the Adapter pattern (synchronous lookup)
  └─ No → does an external BC need to be notified once my BC's domain work finishes?
                └─ Yes → an Integration Event (asynchronous)
                └─ No → no external BC call is needed
```

---

### Synchronous calls — the Adapter pattern (ACL)

Used when you need to **look something up immediately, within the current request**, from an external BC's service.

The Adapter acts as an **Anticorruption Layer (ACL)**. Even if the external BC's model/interface changes, the internal domain model is unaffected.

```
[Order BC Application] → UserAdapter (interface) → UserAdapterImpl → [User BC Service]
                         (my application/adapter/)  (my infrastructure/)
```

**Fits when:**
- An order-detail response needs to include the user's name
- Checking the balance before processing a payment

**Watch out for:**
- Never inject an external BC's Repository or Service directly into the Application layer.
- Never call an external BC's **write methods** through an Adapter. If a write is needed, consider switching to an Integration Event.

The nestjs harness checks whether `application/**/*.ts` directly imports another BC's `domain/*-repository.ts` via
`no-cross-bc-repository-in-application.evaluator.ts` — importing a Repository within the same domain (the normal
pattern) isn't a target.

---

### Asynchronous calls — Integration Events

Used when, after my BC's domain work completes, **an external BC needs to react and change its state**.

```
[Order BC] → Domain Event → Application EventHandler → Integration Event → Outbox → message queue
                                                                                      ↓
                                                              [Payment BC] ← IntegrationEventController
```

**Fits when:**
- After an order is cancelled, the Payment BC needs to process a refund
- After an order completes, the Notification BC needs to send an email

**Watch out for:**
- An Integration Event never exposes an internal Domain Event to the outside as-is. The Application EventHandler is the conversion point.
- The receiving side assumes at-least-once delivery and implements handling idempotently.

**A real example — a compensating action:** after the Payment BC checks the account's active status and
balance via a synchronous Adapter and marks the payment complete (`payment.completed.v1`), the Account BC
subscribes to that and performs the actual deduction (`withdraw`) — there's a brief eventual-consistency window
between the synchronous check and the asynchronous deduction. If the payment is later cancelled
(`payment.cancelled.v1`), the Account BC subscribes the same way and runs `deposit()` as a **compensating credit
that reverses the amount already deducted** — not a separate transaction rollback, but a classic form of a
cross-BC compensating transaction that offsets an earlier state change with a new asynchronous event. Refund
approval (`refund.approved.v1`) reuses the same reaction (a credit). nestjs implementation:
`implementations/nestjs/examples/src/account/interface/integration-event/account-integration-event-controller.ts`,
`implementations/nestjs/examples/src/payment/application/event/`.

---

### Mixing both patterns

One use case can use both patterns together.

```typescript
// Cancelling an order — a synchronous lookup + asynchronous follow-up
public async cancelOrder(command: CancelOrderCommand): Promise<void> {
  // 1. A synchronous lookup via an Adapter (needed for the response)
  const user = await this.userAdapter.findUsers({ userId: command.userId, take: 1, page: 0 })
                  .then((r) => r.users.pop())
  if (!user) throw new Error('User not found.')

  const order = await this.orderRepository.findOrders({ orderId: command.orderId, take: 1, page: 0 })
                  .then((r) => r.orders.pop())
  if (!order) throw new Error('Order not found.')

  order.cancel(command.reason)

  // 2. save → Domain Event → Integration Event (requesting a refund from the Payment BC is asynchronous)
  await this.transactionManager.run(async () => {
    await this.orderRepository.saveOrder(order)
  })
}
```

---

### Mapping to Context Map patterns

| Context Map pattern | Implementation |
|----------------|----------|
| ACL (Anticorruption Layer) | The Adapter pattern — prevents contamination from an external model |
| OHS/PL (Open Host Service / Published Language) | Publishing an Integration Event — with an explicit version (`order.cancelled.v1`) |
| Conformist | Using an external BC's model directly, with no Adapter (not recommended) |
| Customer-Supplier | A combination of Adapter + Integration Event |

---

### Related docs

- [strategic-ddd.md](strategic-ddd.md) — an overview of Context Map patterns
- [domain-events.md](domain-events.md) — details on publishing/receiving Integration Events
