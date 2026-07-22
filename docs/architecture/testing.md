# Testing Strategy

Tests are organized into 3 layers. Each layer differs in what it verifies and its dependency strategy.

| Layer | What it verifies | Dependency strategy | Execution speed |
|--------|----------|------------|----------|
| Domain unit tests | Aggregate, Value Object, Domain Service | No framework (pure business logic) | Very fast |
| Application unit tests | Command/Query Service, Handler | Mocks the Repository, Adapter | Fast |
| E2E tests | The whole path: Interface → Application → Infrastructure | A real DB (in-memory or a container) | Slow |

---

### Domain unit tests

Verify pure business logic with no framework. Runs very fast since there are no external dependencies.

**What's tested:** Aggregate invariants, domain methods, Value Object equality, a Domain Service's decision logic

```typescript
// order/domain/order.spec.ts
describe('Order', () => {
  const createOrder = (overrides = {}) => new Order({
    orderId: 'order-1',
    userId: 'user-1',
    items: [{ itemId: 'item-1', quantity: 2, price: 1000 }],
    status: 'pending',
    ...overrides
  })

  it('throws an error on creation when the order items are empty', () => {
    expect(() => createOrder({ items: [] }))
      .toThrow('An order must have at least one item.')
  })

  it('throws an error when cancelling an already-cancelled order', () => {
    const order = createOrder({ status: 'cancelled' })
    expect(() => order.cancel('changed my mind')).toThrow('This order has already been cancelled.')
  })

  it('collects an OrderCancelled event on cancel', () => {
    const order = createOrder()
    order.cancel('changed my mind')
    expect(order.domainEvents).toHaveLength(1)
    expect(order.domainEvents[0]).toBeInstanceOf(OrderCancelled)
  })
})
```

**Principles:**
- Give test fixtures default values via a helper function (`createOrder`), varied via `overrides`
- Reference error messages via the enum (never hardcode the string)
- Never import a framework module

---

### Application unit tests

Replace external dependencies like the Repository and Adapter with **mocks**. Verify the use case's coordination logic.

**What's tested:** a Command Service's coordination flow, error propagation, the transaction boundary

```typescript
// order/application/command/order-command-service.spec.ts
describe('OrderCommandService', () => {
  let service: OrderCommandService
  let orderRepository: MockRepository

  beforeEach(() => {
    orderRepository = {
      findOrders: jest.fn(),
      saveOrder: jest.fn(),
      deleteOrder: jest.fn()
    }
    service = new OrderCommandService(orderRepository, mockTransactionManager)
  })

  it('throws an error when the order does not exist', async () => {
    orderRepository.findOrders.mockResolvedValue({ orders: [], count: 0 })

    await expect(service.cancelOrder({ orderId: 'non-existent', reason: 'changed my mind' }))
      .rejects.toThrow(OrderErrorMessage['Order not found.'])
  })

  it('calls saveOrder when cancelling an order', async () => {
    const order = new Order({ orderId: 'order-1', userId: 'user-1',
      items: [{ itemId: 'i1', quantity: 1, price: 1000 }], status: 'pending' })
    orderRepository.findOrders.mockResolvedValue({ orders: [order], count: 1 })

    await service.cancelOrder({ orderId: 'order-1', reason: 'changed my mind' })

    expect(orderRepository.saveOrder).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'cancelled' })
    )
  })
})
```

**Principles:**
- Type the Repository mock as the abstract class (never mock the concrete class)
- The mock must match the abstract class's method signatures exactly
- Business logic is verified in the Domain unit tests. An Application test only verifies the coordination flow

---

### E2E tests

Verify the whole path — Interface → Application → Infrastructure — against a real DB.

**What's tested:** HTTP endpoint integration, actual DB save/lookup, transaction rollback

```typescript
// test/order.e2e-spec.ts
describe('OrderController (e2e)', () => {
  let app: Application
  let db: Database

  beforeAll(async () => {
    db = await setupInMemoryDb()
    app = await createApp({ db })
  })

  it('GET /orders/:orderId — returns an existing order', async () => {
    const orderId = await createTestOrder(db)

    const response = await request(app).get(`/orders/${orderId}`)
      .set('Authorization', `Bearer ${testToken}`)

    expect(response.status).toBe(200)
    expect(response.body.orderId).toBe(orderId)
  })

  it('GET /orders/:orderId — returns 404 for a nonexistent order', async () => {
    const response = await request(app).get('/orders/non-existent')
      .set('Authorization', `Bearer ${testToken}`)

    expect(response.status).toBe(404)
    expect(response.body.code).toBe('ORDER_NOT_FOUND')
  })

  afterAll(() => app.close())
})
```

**Principles:**
- E2E tests use an in-memory DB (SQLite, etc.) or a container (testcontainers). Never use the production DB
- Each test must be runnable independently (never share state between tests)
- Verify via real HTTP requests — never bypass the framework internals

---

### Test-file placement

```
src/
  order/
    domain/
      order.spec.ts                      ← Domain unit tests (next to the source)
    application/
      command/
        order-command-service.spec.ts    ← Application unit tests (next to the source)
test/
  order.e2e-spec.ts                      ← E2E tests (a separate directory)
```

---

### Test-naming pattern

```
<action>_when_<condition>_then_<expected result>
Example: cancelOrder_when_alreadyCancelled_then_throwsAnError
```

---

### Related docs

- [tactical-ddd.md](tactical-ddd.md) — Domain-layer design (what unit tests target)
- [layer-architecture.md](layer-architecture.md) — the Application Service (what Application unit tests target)
- [error-handling.md](error-handling.md) — the error-response format (a verification point for E2E tests)
