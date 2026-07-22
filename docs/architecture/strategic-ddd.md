# Strategic Design — Subdomain, Bounded Context, Context Map

This is the **problem-space analysis** done before tactical design (Aggregate, Repository, Domain Event). It decides which areas to invest in and how much, where to draw BC boundaries, and how to manage relationships between BCs.

---

### Subdomain classification

Split the business domain into functional areas and classify each area's strategic value.

| Type | Definition | Implementation strategy |
|------|------|----------|
| **Core Domain** | This business's own competitive edge. Must be built in-house, and needs the most investment | Apply the full set of DDD tactical patterns. Collaborate closely with domain experts |
| **Supporting Subdomain** | Supports the Core, but isn't a differentiator | Build in-house, but keep it simpler than Core. CQRS can be skipped |
| **Generic Subdomain** | Needed the same way regardless of industry | Prefer adopting an external solution (auth, notifications, payment gateway, etc.) |

**Example — e-commerce:**

```
Core Domain      : Orders, product recommendations
Supporting       : Inventory, shipment tracking
Generic          : Auth (Auth0), sending emails (SES), payments (a PG)
```

> Focus time and design effort on the Core Domain. Keep Supporting simple, and don't build Generic in-house.

---

### Bounded Context

A **Bounded Context (BC)** is the explicit boundary within which a particular domain model holds. The same word can mean different things depending on the BC.

#### Criteria for identifying BC boundaries

- **Where a term's meaning changes**: "Product" means display info in the Catalog BC, but a purchased line item in the Order BC
- **A unit that must be independently deployable/changeable**
- **An area a different team owns**

#### Mapping onto modules/services

**1 Bounded Context = 1 independent module/service**. A BC boundary is a code-module boundary.

```
Order BC   → OrderModule   (src/order/ or order-service/)
User BC → UserModule    (src/user/ or user-service/)
Payment BC   → PaymentModule (src/payment/ or payment-service/)
```

Each BC's internal implementation (Aggregate, Repository, Service) is never exposed externally. Another BC only ever uses a published interface (an exported service, an Integration Event).

#### Ubiquitous Language

Within a BC, developers and domain experts use **the same terminology**. The class/method names in the code ARE the Ubiquitous Language.

```typescript
// Order BC — "Order" is the transactional unit holding purchase intent
export class Order { ... }

// Shipping BC — "Order" is a physical dispatch work unit (same word, different meaning → different BC)
export class ShipmentOrder { ... }
```

Sharing a model across BC boundaries creates terminology confusion and coupling. Each BC keeps its own model independent.

---

### Context Map

Defines the type of relationship between BCs. The relationship type determines the implementation approach.

#### Relationship types and their implementation

| Pattern | Description | Implementation |
|------|------|----------|
| **ACL** (Anticorruption Layer) | The downstream BC adds a translation layer to keep the upstream model's contamination out | The Adapter pattern — translates the external model into the internal domain model |
| **OHS/PL** (Open Host Service / Published Language) | The upstream BC provides a stable published contract | Publishing an Integration Event — with an explicit version (`order.cancelled.v1`) |
| **Customer-Supplier** | The downstream (Customer) communicates requirements to the upstream (Supplier) and they collaborate | A combination of Adapter + Integration Event. Requires agreeing on the interface with the upstream team |
| **Conformist** | The downstream follows the upstream model as-is. No translation | Uses the external model directly — not recommended, since it's fragile to upstream changes |
| **Shared Kernel** | Two BCs share part of a model | A shared module (`shared/`). Both sides must agree on changes. Keep it to a minimum |
| **Partnership** | Two teams succeed or fail together, so they collaborate closely | Direct dependencies between modules are allowed. Use sparingly, since it makes independent deployment harder |

#### Example Context Map diagram

```
[Order BC] --ACL--> [User BC]          ← Order looks up user info via an Adapter
[Order BC] --OHS/PL--> [Payment BC]         ← Order publishes an Integration Event, Payment receives it
[Order BC] --ACL--> [external shipping API]      ← an external system is always isolated behind an ACL
[Auth BC] --OHS/PL--> [Order/Payment/User BCs]  ← Auth is a published service used by every BC
```

#### Where each pattern is implemented

**ACL (the Adapter pattern)**

```
order/
  application/adapter/user-adapter.ts          ← the interface (abstract class)
  infrastructure/user-adapter-impl.ts          ← the implementation
```

**OHS/PL (an Integration Event)**

```
order/
  application/integration-event/order-cancelled-integration-event.ts  ← the published contract
  application/event/order-cancelled-handler.ts                        ← publishes it
payment/
  interface/integration-event/payment-integration-event-controller.ts ← receives it
```

→ See [domain-events.md](domain-events.md) for the detailed implementation

---

### Design order

Strategic design happens before tactical design.

```
1. Subdomain classification       — identify Core / Supporting / Generic
2. Identify BCs              — based on terminology boundaries, team boundaries, deployment units
3. Draw up the Context Map     — decide the relationship type between BCs
4. Define the Ubiquitous Language — write a glossary of core terms per BC
       ↓
5. Tactical design (Aggregate, Repository, Domain Event, Application Service)
```

Move into tactical design only after agreeing on the strategic-design deliverables (the Subdomain classification table, the BC definitions, the Context Map).

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object, Domain Event
- [cross-domain-communication.md](cross-domain-communication.md) — the criteria for choosing a communication pattern between BCs
- [domain-events.md](domain-events.md) — details on publishing/receiving Integration Events
