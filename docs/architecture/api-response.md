# API Response Structure

### Pagination

Offset-based pagination is the default.

| Parameter | Type | Description | Default |
|---------|------|------|--------|
| `page` | number | Page number (starts at 0) | 0 |
| `take` | number | Page size | 20 |
| `sort` | string | Sort key (`createdAt:desc`) | optional |

```
GET /orders?page=0&take=20&status=pending&status=paid&sort=createdAt:desc
```

**Why page starts at 0:** it makes the `skip = page * take` computation natural. With `page=0`, `skip=0` gets the first page.

---

### List-lookup response format

```json
{
  "orders": [
    { "orderId": "abc123", "status": "pending", "totalAmount": 30000 }
  ],
  "count": 42
}
```

**Principles:**
- The key name is the **plural of the domain object's name** (`orders`, `users`, `payments`)
- Never use a generic key like `result`, `data`, or `items`
- `count` is the total after filters are applied (not the current page's size)

---

### Single-record-lookup response format

```json
{
  "orderId": "abc123",
  "status": "pending",
  "totalAmount": 30000,
  "items": [
    { "itemId": "item-1", "quantity": 2, "price": 15000 }
  ]
}
```

Never wrap it in a generic envelope (`{ success: true, data: { ... } }`). Return the domain object directly.

**Why not use a generic envelope:** the HTTP status code already distinguishes an error from a normal response. An envelope is redundant, and adds an unnecessary unwrapping layer to client code.

---

### Repository lookup-method return shape

A list-lookup method always returns **an array of domain objects + a count**.

```typescript
// domain/order-repository.ts
export abstract class OrderRepository {
  abstract findOrders(query: {
    orderId?: string
    userId?: string
    status?: string[]
    take: number
    page: number
  }): Promise<{ orders: Order[]; count: number }>
}
```

---

### Single-record lookup — no separate method

Don't build a separate `findOne` method. Pass `take: 1` and pull the record out via `.then()` chaining.

```typescript
const order = await this.orderRepository
  .findOrders({ orderId, take: 1, page: 0 })
  .then((r) => r.orders.pop())

if (!order) throw new Error(OrderErrorMessage['Order not found.'])
```

**Why:** keeping `findOne` and `findMany` as separate methods duplicates things like the dynamic filter conditions. Unifying the lookup into a single path keeps the Repository implementation simpler.

---

### The dynamic filter-condition pattern

In a list-lookup query, only apply a condition **when its value is present**.

```typescript
// an infrastructure implementation (conceptual)
public async findOrders(query: FindOrdersQuery) {
  const conditions: Condition[] = []

  if (query.orderId) conditions.push(eq('orderId', query.orderId))
  if (query.userId)  conditions.push(eq('userId', query.userId))
  if (query.status?.length) conditions.push(inArray('status', query.status))

  return queryDb({ conditions, take: query.take, skip: query.page * query.take })
}
```

Including an `undefined` condition can unintentionally return everything, or cause a query error. Wrap each condition in an `if` guard.

---

### Result object design

The Result object a Query Service returns defines the response schema. It never returns a domain Aggregate directly.

```typescript
// application/query/get-orders-result.ts
export class GetOrdersResult {
  public readonly orders: GetOrderResult[]
  public readonly count: number
}

export class GetOrderResult {
  public readonly orderId: string
  public readonly status: string
  public readonly totalAmount: number
  public readonly createdAt: Date
}
```

**Why a domain Aggregate is never exposed directly as a response:**
- An Aggregate includes business logic and internal state. Serializing it exposes internal implementation externally.
- A Result object that includes only the fields the lookup needs is lighter than the Aggregate, and more flexible to change.

---

### Machine-readable API documentation (OpenAPI)

Every REST endpoint must be documented in a machine-readable OpenAPI schema, generated from the same annotations/type hints the framework already uses for request/response validation — not maintained as a separate hand-written document that can drift from the real routes.

**Minimum bar for "documented" (checked mechanically, not just present):**
- Every operation has a `summary` and a `description` — an operation ID alone (or a bare route with no metadata) is not sufficient.
- Every non-2xx status code the handler can actually return is declared, with a description of what causes it — cross-check against the handler's own error-mapping table (see [error-handling.md](error-handling.md)). Only documenting the success response is the most common way this rots: it looks complete because the page renders, but a client has no way to know what a 404 or 409 looks like.
- Every request/response field has a `description` — a bare property with no explanation forces the reader to guess from the field name alone.

**Why this is a repo-wide convention and not a per-implementation nicety:** documentation that "looks done" (the endpoint appears in the docs UI at all) is easy to mistake for actually being useful, so the completeness bar above is deliberately explicit rather than left to judgment — see `docs/checklist.md`'s REST API endpoints section and each language's own harness rule enforcing it.

---

### Related docs

- [repository-pattern.md](repository-pattern.md) — Repository method design
- [layer-architecture.md](layer-architecture.md) — the Query Service, Result objects
- [conventions.md](../conventions.md) — REST API URL design principles
- [error-handling.md](error-handling.md) — the error-response schema every non-2xx response should reference
