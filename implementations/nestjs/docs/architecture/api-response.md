# Pagination / Common Response Pattern

Defines the pagination and response structure of list-lookup APIs.

## Pagination Approach

Uses offset-based pagination as the default.

| Parameter | Type | Description | Default |
|---------|------|------|--------|
| `page` | number | The page number (0-based) | 0 |
| `take` | number | The page size | 20 |
| `sort` | string | The sort criterion (`createdAt:desc`) | optional |

```
GET /orders?page=0&take=20&status=pending&status=paid&sort=createdAt:desc
```

## Query DTO

```typescript
// src/order/application/query/get-orders-query.ts
export class GetOrdersQuery {
  @Type(() => Number)
  @IsInt()
  @Min(0)
  readonly page: number = 0

  @Type(() => Number)
  @IsInt()
  @Min(1)
  readonly take: number = 20

  @IsOptional()
  @IsEnum(OrderStatus, { each: true })
  readonly status?: OrderStatus[]

  @IsOptional()
  @IsString()
  readonly sort?: string
}
```

- `@Type(() => Number)`: since the querystring is a string, convert it to a number
- Give `page` and `take` default values so the client can omit them

## Repository Interface

A list-lookup method always returns **a domain object array + count**. Use the plural of the domain object's name for the key.

```typescript
// src/order/domain/order-repository.ts
export abstract class OrderRepository {
  abstract findOrders(query: {
    orderId?: string
    userId?: string
    status?: OrderStatus[]
    take: number
    page: number
  }): Promise<{ orders: Order[]; count: number }>

  abstract saveOrder(order: Order): Promise<void>
  abstract deleteOrder(orderId: string): Promise<void>
}
```

### Single-Record Lookup Pattern

Don't create a separate `findOne` method. Pass `take: 1` to `findOrders` and convert with `.then()` chaining.

```typescript
const order = await this.orderRepository
  .findOrders({ orderId, take: 1, page: 0 })
  .then((r) => r.orders.pop())

if (!order) throw new Error(OrderErrorMessage['Order not found.'])
```

## Repository Implementation — the QueryBuilder Pattern

```typescript
// src/order/infrastructure/order-repository-impl.ts
async findOrders(query: {
  orderId?: string
  userId?: string
  status?: OrderStatus[]
  take: number
  page: number
}): Promise<{ orders: Order[]; count: number }> {
  const qb = this.manager
    .createQueryBuilder(OrderEntity, 'order')
    .leftJoinAndSelect('order.items', 'item')

  // dynamic where conditions
  if (query.orderId) {
    qb.andWhere('order.orderId = :orderId', { orderId: query.orderId })
  }
  if (query.userId) {
    qb.andWhere('order.userId = :userId', { userId: query.userId })
  }
  if (query.status) {
    qb.andWhere('order.status IN (:...status)', { status: query.status })
  }

  qb.take(query.take).skip(query.page * query.take)

  const [entities, count] = await qb.getManyAndCount()
  return { orders: entities.map(this.toDomain), count }
}
```

### Dynamic Where Condition Rules

- Wrap each condition in an `if (query.field)` guard, applying it only when a value is present
- Use `IN (:...param)` spread syntax for array conditions
- Accumulate conditions with `andWhere` — use `where` only for the first call, or delegate it to the QueryBuilder

## Response Structure

### List-Lookup Response

The Controller wraps the Query result in a Response DTO.

```typescript
// src/order/interface/dto/get-orders-response-body.ts
export class GetOrdersResponseBody {
  @ApiProperty({ type: [GetOrderResponseBody] })
  readonly orders: GetOrderResponseBody[]

  @ApiProperty()
  readonly count: number
}
```

```json
{
  "orders": [
    { "orderId": "abc123", "status": "pending", "totalAmount": 30000 }
  ],
  "count": 42
}
```

- The key name is the plural of the domain object name (`orders`, `users`, `payments`)
- Never use a generic key like `result`, `data`, `items`

`harness/evaluators/rules/no-generic-response-keys.evaluator.ts` catches it as `no-generic-response-keys.generic-list-field` when an array field sitting alongside `count: number` in an application/interface layer class is named `result`/`data`/`items`. Whether a Query handler/Controller returns the Domain Aggregate as-is is caught by `harness/evaluators/rules/query-handler-no-raw-aggregate.evaluator.ts` as `query-handler-no-raw-aggregate.raw-aggregate-return`.

### Single-Record Lookup Response

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

Never wrap it in a generic wrapper (`{ success: true, data: { ... } }`). Return the domain object directly.

## Principles

- **Offset-based pagination by default**: use the `page` (0-based), `take` (page size) parameters.
- **No single-record lookup method**: use the `findOrders({ take: 1 }).then(r => r.orders.pop())` pattern.
- **Response keys are domain plurals**: `{ orders: [...], count: N }` — generic keys (`data`, `result`) are prohibited.
- **No generic wrapper**: don't use a `{ success, data }` pattern. Distinguish errors via the HTTP status code.
- **Dynamic where via conditional chaining**: accumulate conditions with the `if (query.field) qb.andWhere(...)` pattern.
