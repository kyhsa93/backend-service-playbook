# The Domain Service / Technical Service Pattern

### When you need a Domain Service

Use one when there's domain logic that **can't go in an Aggregate Root**.

| Situation | Why |
|---|---|
| Logic that needs to read several Aggregates to make a judgment | One Aggregate never references another Aggregate directly |
| A domain rule that doesn't belong to a single Aggregate | A rule where it's unclear which Aggregate should own it |
| A domain operation that involves calling an external service | An Aggregate has no external I/O |

> A Domain Service **holds no state**. It only holds logic. If it needs state, reconsider the design.

---

### Placement and naming

- File location: `<domain>/domain/<domain-service-name>.ts`
- Class name: a name expressing the domain action (`OrderPricingService`, `StockValidationService`)
- Since it lives in the Domain layer, **it never uses a framework decorator**
- An Application Service uses it via constructor injection

```typescript
// domain/order-pricing-service.ts — a Domain Service
export class OrderPricingService {
  public calculateDiscount(
    order: Order,
    coupon: { discountAmount: number; minimumAmount: number; isExpired: () => boolean }
  ): number {
    if (coupon.isExpired()) throw new Error(OrderErrorMessage['This coupon has expired.'])
    if (order.getTotalAmount() < coupon.minimumAmount) return 0
    return Math.min(coupon.discountAmount, order.getTotalAmount())
  }
}
```

```typescript
// application/command/order-command-service.ts — an example Command Service calling the Domain Service
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly orderPricingService: OrderPricingService
  ) {}

  public async applyCoupon(command: ApplyCouponCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(OrderErrorMessage['Order not found.'])

    const discount = this.orderPricingService.calculateDiscount(order, command.coupon)
    order.applyDiscount(discount)

    await this.orderRepository.saveOrder(order)
  }
}
```

---

### A real, working example — RefundEligibilityService (coordinating across Aggregates)

The `OrderPricingService` example above is actually just a combination of a single Aggregate (`Order`) and a value object (`coupon`), so it isn't really an example of "logic that needs to read several Aggregates to make a judgment." Below is an example that **actually loads two independent Aggregates together and coordinates them** — it directly cites real code from the nestjs implementation (`implementations/nestjs/examples/src/payment/`). The pattern itself is framework-agnostic and applies the same way in other languages.

**The domain rule**: "A refund requires the original payment to be in the COMPLETED state, and the refund amount can't exceed the payment amount."

- The `Payment` Aggregate doesn't know about a refund attempt (`Refund`) against it — a refund only ever exists as a separate Aggregate.
- The `Refund` Aggregate doesn't know the original payment's amount/status — it only references it via `paymentId`.

Putting this judgment into either Aggregate's own method would mean that Aggregate has to take the entire other Aggregate as a parameter, breaking the Aggregate boundary. So this judgment lives in a separate Domain Service that the Application layer, having loaded both Aggregates, delegates to:

```typescript
// domain/refund-eligibility-service.ts — a Domain Service (no framework decorator)
export interface RefundDecision {
  readonly approved: boolean
  readonly reason?: string
}

export class RefundEligibilityService {
  // classification is a plain value already computed upstream by RefundReasonClassifier (a
  // Technical Service wrapping an LLM call — see the Technical Service section below). This
  // method never calls it and doesn't know an LLM produced the value; it only weighs the
  // fraud-risk signal alongside its other checks and still owns the actual judgment.
  public evaluate(payment: Payment, refund: Refund, classification: RefundReasonClassification): RefundDecision {
    if (payment.status !== PaymentStatus.COMPLETED) {
      return { approved: false, reason: PaymentErrorMessage['A refund can only be requested for a completed payment.'] }
    }
    if (refund.amount > payment.amount) {
      return { approved: false, reason: PaymentErrorMessage['The refund amount cannot exceed the payment amount.'] }
    }
    if (classification.category === 'fraud_suspected' && classification.fraudRiskScore >= 0.7) {
      return { approved: false, reason: PaymentErrorMessage['This refund reason was flagged as high fraud risk and requires manual review.'] }
    }
    return { approved: true }
  }
}
```

```typescript
// application/command/request-refund-command-handler.ts — loads both Repositories, classifies
// the reason via the Technical Service, and delegates
export class RequestRefundCommandHandler {
  private readonly refundEligibilityService = new RefundEligibilityService()

  constructor(
    private readonly paymentRepository: PaymentRepository,
    private readonly refundRepository: RefundRepository,
    private readonly refundReasonClassifier: RefundReasonClassifier // a Technical Service, DI-injected
  ) {}

  public async execute(command: RequestRefundCommand): Promise<Refund> {
    const payment = await this.paymentRepository
      .findPayments({ paymentId: command.paymentId, ownerId: command.requesterId, take: 1, page: 0 })
      .then((r) => r.payments.pop())
    if (!payment) throw new Error(PaymentErrorMessage['Payment not found.'])

    const refund = Refund.create({ paymentId: payment.paymentId, amount: command.amount, reason: command.reason })
    const classification = await this.refundReasonClassifier.classify(command.reason)

    const decision = this.refundEligibilityService.evaluate(payment, refund, classification)
    if (decision.approved) refund.approve({ accountId: payment.accountId, ownerId: payment.ownerId })
    else refund.reject(decision.reason ?? 'The refund request was rejected.')

    await this.refundRepository.saveRefund(refund)
    return refund
  }
}
```

`RefundEligibilityService` holds no state and is used by instantiating it directly with `new` — it's never registered in a DI container (staying true to the "never uses a framework decorator" principle from the "Placement and naming" section above). Its unit test also doesn't go through the Application layer — it `new`s this class directly, passes in a plain `RefundReasonClassification` value (no LLM call, no mocking needed), and verifies only the decision logic. `RefundReasonClassifier` — the Technical Service that produces that value from the refund's free-text reason via an LLM call — is a real, worked example of the Technical Service pattern in the section below.

Full code: `implementations/nestjs/examples/src/payment/domain/refund-eligibility-service.ts`,
`payment.ts`, `refund.ts`, `application/command/request-refund-command-handler.ts`.

kotlin-springboot's harness `no-cross-aggregate-reference` rule mechanically checks, within `payment/domain/`, that `Payment` doesn't hold `Refund` as a field (and vice versa) — only an ID reference is allowed. The legitimate pattern where a Domain Service takes two Aggregates as function parameters, like `RefundEligibilityService.evaluate(payment: Payment, refund: Refund)`, isn't a target of this rule.

The nestjs harness verifies the same rule too — `no-cross-aggregate-reference.evaluator.ts` checks whether `payment.ts` directly imports `Refund` as a type (a named import), and vice versa for `refund.ts`. It's a regression guard verifying that the two Aggregates only reference each other via an ID like `paymentId: string`, never by importing each other's type.

---

### Domain Service vs. Application Service vs. Technical Service

The difference between three easily-confused concepts (see the section below for Technical Service):

| | Domain Service | Application Service | Technical Service |
|---|---|---|---|
| Layer | Domain | Application (the interface) | Application (the interface) / Infrastructure (the implementation) |
| Role | Computing/judging a domain rule | Coordinating a use case (calling the Repository, transactions) | Abstracting a technical concern (encryption, storage, an external API, etc.) |
| State | None | None | None (except internal implementation details like a cache) |
| Dependencies | Only other domain objects | Repository, Domain Service, Adapter, Technical Service | An external library/SDK (implementation only) |
| Framework dependency | None | None (though it's registered as a DI container token) | None (for the interface); the implementation can depend on an SDK |
| Errors | A plain Error | A plain Error | A plain Error |

The **Application Service** coordinates the use case, the **Domain Service** handles the domain judgment inside it, and the **Technical Service** handles the part where a technical implementation is the crux of it.

---

## Technical Service — separating out technical-infrastructure concerns

For a feature **whose core is a technical implementation** — encryption, file storage, Secrets Manager, an external API client, a message-queue client, etc. — define an interface (an abstract class/interface) in the Application layer, and provide the implementation in the Infrastructure layer.

**Why:**
- The Application Service never depends directly on a specific library/SDK.
- Even if the implementation technology changes (e.g. AES → KMS, S3 → GCS), only the implementation needs to be swapped.
- In tests, mock the interface to unit-test it with no external dependency.

**Difference from an Adapter** (see [cross-domain-communication.md](cross-domain-communication.md)):
- **Adapter**: an interface for calling another Bounded Context's Service (communication between domains)
- **Technical Service**: an interface for abstracting a technical-infrastructure implementation (separating a technical concern, unrelated to any domain)

**Placement principle — inside the domain is the default.** Put a Technical Service inside the domain that needs it (`<domain>/application/service/`, `<domain>/infrastructure/`). Only consider promoting it to a top-level shared module once multiple domains **actually** end up sharing the same implementation (YAGNI) — don't split it out to the top level in advance just because "other domains might use it someday." This is also why the example below is placed inside `[the Order domain]`.

```
[Order domain]
  application/
    service/
      crypto-service      (the interface)             ← defined in the shape the Application needs
    command/
      order-command-service  (injected with CryptoService)
  infrastructure/
    crypto-service-impl   (the real implementation, e.g. AES)
```

**Step 1 — define the interface in the Application layer**

```typescript
// application/service/crypto-service — the interface
abstract class CryptoService {
  abstract encrypt(plainText: string): Promise<string>
  abstract decrypt(cipherText: string): Promise<string>
}
```

Define the interface in **the shape the consuming side (the Application Service) needs**. Never expose implementation-technology details (the algorithm, key management, etc.) in the interface.

**Step 2 — write the implementation in the Infrastructure layer**

```typescript
// infrastructure/crypto-service-impl
class CryptoServiceImpl implements CryptoService {
  async encrypt(plainText: string): Promise<string> { /* the real implementation, e.g. AES */ }
  async decrypt(cipherText: string): Promise<string> { /* ... */ }
}
```

**Step 3 — use it in the Application Service**

```typescript
class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly cryptoService: CryptoService
  ) {}

  async createOrder(command: CreateOrderCommand): Promise<void> {
    const encryptedAddress = await this.cryptoService.encrypt(command.address)
    // ...
  }
}
```

**Step 4 — register it with DI**: bind the interface → implementation in the framework's DI container (`{ provide: CryptoService, useClass: CryptoServiceImpl }` or the per-language equivalent).

> **When to apply this**: don't split out a simple utility function (date formatting, string conversion, etc.) as a Technical Service. Apply it to a technical concern that involves integrating with an external system, or where the implementation technology might get swapped out.
> Examples: encryption, file storage ([file-storage.md](file-storage.md)), Secrets Manager ([secret-manager.md](secret-manager.md)), a message-queue client, an external API client, sending email/SMS, etc.

**A real, working example — RefundReasonClassifier (an LLM call as a Technical Service).** An external LLM call is a technical concern like any other in this pattern — the interface is defined in the shape the Domain Service needs (a category plus a fraud-risk score, see `RefundReasonClassification` above), and the implementation is free to swap providers/models without the Application or Domain layer ever changing:

```typescript
// application/service/refund-reason-classifier.ts — the interface
export abstract class RefundReasonClassifier {
  abstract classify(reason: string): Promise<RefundReasonClassification>
}
```

The implementation (`infrastructure/refund-reason-classifier-impl.ts`) calls the Claude API with a JSON-schema-constrained response and falls back to a neutral, non-blocking result (`{ category: 'other', fraudRiskScore: 0 }`) on any failure — a classification outage must never block a refund request, so the failure is swallowed at this Infrastructure boundary rather than surfaced as a domain error. Full code: `implementations/nestjs/examples/src/payment/application/service/refund-reason-classifier.ts`, `infrastructure/refund-reason-classifier-impl.ts`.

---

### A misuse of Domain Service

**Wrong example: a DB lookup inside a Domain Service**

```typescript
// wrong — a Domain Service using the Repository directly
export class OrderValidationService {
  constructor(private readonly orderRepository: OrderRepository) {} // ← forbidden

  public async validateOrder(orderId: string): Promise<boolean> {
    const { orders } = await this.orderRepository.findOrders(...)  // ← forbidden
    ...
  }
}
```

A Domain Service takes an already-loaded domain object and only judges it. The lookup itself is the Application Service's responsibility.

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity design
- [layer-architecture.md](layer-architecture.md) — separating responsibilities per layer
- [error-handling.md](error-handling.md) — the error-message enum pattern
- [cross-domain-communication.md](cross-domain-communication.md) — the Adapter pattern (how it differs from Technical Service)
- [secret-manager.md](secret-manager.md), [file-storage.md](file-storage.md) — examples of applying Technical Service
