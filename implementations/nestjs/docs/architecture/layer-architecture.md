# Layer Architecture

### Dependency Direction

```
Interface (Controller)  →  Application (Service)  →  Domain (Aggregate, Repository interface)
                                                          ↑
                                                   Infrastructure (Repository implementation)
```

- A higher layer may depend on a lower layer, but a lower layer never depends on a higher layer.
- The Domain layer doesn't depend on any layer (including the framework and the ORM).
- The Infrastructure layer implements the Domain layer's interfaces (dependency inversion).

### Domain Layer Responsibilities

The domain layer is the core of the business rules. Written in pure TypeScript, with no framework dependency.

> **Framework independence principle**: don't use NestJS decorators like `@Injectable`, `@Module` in the Domain layer. The error message enum (`@/order/order-error-message`), however, is imported and referenced.
> The Application layer (Query/Result/Command) may use NestJS/Swagger decorators like `@ApiProperty`, `class-validator`.

1. **Aggregate Root** — encapsulates business rules and invariants. State changes must always go through the Aggregate Root's methods.
2. **Entity** — an object with a unique identifier and a lifecycle.
3. **Value Object** — an immutable object. Equality is judged by the combination of its attributes. Implements an `equals()` method to support attribute-based comparison.
4. **Domain Event** — a data class representing a significant event that occurred in the domain.
5. **Repository interface** — an abstract class defined at the Aggregate Root level. Its implementation lives in the Infrastructure layer.

```typescript
// domain/order.ts — an Aggregate Root example (framework-independent)
import { OrderCancelled } from '@/order/domain/order-cancelled'
import { OrderItem } from '@/order/domain/order-item'
import { OrderErrorMessage } from '@/order/order-error-message'

export type OrderDomainEvent = OrderCancelled

export class Order {
  public readonly orderId: string
  public readonly userId: string
  public readonly items: OrderItem[]
  private _status: 'pending' | 'paid' | 'cancelled'
  private readonly _events: OrderDomainEvent[] = []

  constructor(params: { orderId: string; userId: string; items: OrderItem[]; status: 'pending' | 'paid' | 'cancelled' }) {
    if (params.items.length === 0) throw new Error(OrderErrorMessage['An order must have at least one item.'])
    this.orderId = params.orderId
    this.userId = params.userId
    this.items = params.items
    this._status = params.status
  }

  get status(): 'pending' | 'paid' | 'cancelled' { return this._status }
  get domainEvents(): OrderDomainEvent[] { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error(OrderErrorMessage['The order is already cancelled.'])
    if (this._status === 'paid') throw new Error(OrderErrorMessage['A paid order cannot be cancelled.'])
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

```typescript
// domain/order-repository.ts — the Repository interface (abstract class)
export abstract class OrderRepository {
  abstract saveOrder(order: Order): Promise<void>
  abstract findOrders(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
    readonly userId?: string
    readonly status?: string[]
  }): Promise<{ orders: Order[]; count: number }>
  abstract deleteOrder(orderId: string): Promise<void>
}
```

If `domain/*.ts` (whether its own domain or another domain) imports `application/`·`infrastructure/`·`interface/`,
`harness/evaluators/rules/domain-layer-isolation.evaluator.ts` catches it as
`domain-layer-isolation.forbidden-import`. If state changes aren't exposed only through domain methods,
but instead through a public `set x(...)` accessor or a public non-readonly field,
`harness/evaluators/rules/aggregate-no-public-setters.evaluator.ts` catches it.

### Application Layer Responsibilities — the Coordinator

The Application Service is split into a **Command Service** and a **Query Service**.

#### Command Service (write)

Handles use cases that change data. Injects the Repository to fetch/save the Aggregate. It doesn't perform business logic itself, delegating it to the Aggregate.

1. Fetch the Aggregate from the Repository
2. Call the Aggregate's domain method (business logic is delegated to the Aggregate)
3. Save the Aggregate via the Repository (the Repository saves the Aggregate + outbox in the same transaction internally)
4. Manage the transaction

```typescript
// application/command/order-command-service.ts
import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { OrderRepository } from '@/order/domain/order-repository'
import { PaymentRepository } from '@/order/domain/payment-repository'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Injectable()
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly paymentRepository: PaymentRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async cancelOrder(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(ErrorMessage['Order not found.'])

    order.cancel(command.reason)

    // Repository.saveOrder() saves the Aggregate + outbox together internally
    await this.transactionManager.run(async () => {
      await this.paymentRepository.deletePaymentMethods(order.orderId)
      await this.orderRepository.saveOrder(order)
    })
  }
}
```

#### Query Service (read)

Handles use cases that read data. Instead of using the Repository directly, it injects the application layer's **Query interface** (abstract class). The Query implementation lives in the infrastructure layer.

```typescript
// application/query/order-query.ts — the Query interface (abstract class)
export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(query: GetOrderQuery): Promise<GetOrderResult>
}
```

```typescript
// application/query/order-query-service.ts
import { Injectable } from '@nestjs/common'

import { OrderQuery } from '@/order/application/query/order-query'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'

@Injectable()
export class OrderQueryService {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

```typescript
// infrastructure/order-query-impl.ts — the Query implementation (direct DB access)
import { Injectable } from '@nestjs/common'

import { OrderQuery } from '@/order/application/query/order-query'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'

@Injectable()
export class OrderQueryImpl extends OrderQuery {
  constructor(private readonly dataSource: DataSource) {
    super()
  }

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    // query the DB directly — no need to reconstruct the Aggregate, use a query optimized for reading
  }
}
```

#### Module DI Configuration

```typescript
// order-module.ts
providers: [
  { provide: OrderRepository, useClass: OrderRepositoryImpl },
  { provide: OrderQuery, useClass: OrderQueryImpl },
  OrderCommandService,
  OrderQueryService,
]
```

#### Command/Query Separation Principles

- The **Repository** is used only in the Command Service. It handles fetching/saving at the Aggregate level.
- The **Query interface** is used only in the Query Service. It handles read-optimized lookups, with no need to reconstruct the Aggregate.
- In the Controller, write requests call the Command Service and read requests call the Query Service.
- The `@nestjs/cqrs` module can be used to switch to a Command/Query Bus-based approach. See [cqrs-pattern.md](cqrs-pattern.md) for the detailed pattern.

### Infrastructure Layer Responsibilities

1. **Repository implementation** — implements the Domain layer's abstract class. The only layer that uses the ORM client directly.
2. **Event publishing** — message queue integration, event serialization.
3. **External system adapters** — the Anticorruption Layer. Converts external API responses into the domain model.

```typescript
// infrastructure/order-repository-impl.ts
import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { TransactionManager } from '@/database/transaction-manager'
import { OutboxWriter } from '@/outbox/outbox-writer'
import { Order } from '@/order/domain/order'
import { OrderItem } from '@/order/domain/order-item'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'

@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderItemEntity) private readonly orderItemRepo: Repository<OrderItemEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async findOrders(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
    readonly userId?: string
    readonly status?: string[]
  }): Promise<{ orders: Order[]; count: number }> {
    const qb = this.orderRepo.createQueryBuilder('order')
      .leftJoinAndSelect('order.items', 'item')
      .orderBy('order.orderId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.orderId) qb.andWhere('order.orderId = :orderId', { orderId: query.orderId })
    if (query.userId) qb.andWhere('order.userId = :userId', { userId: query.userId })
    if (query.status?.length) qb.andWhere('order.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    // convert DB entities → domain Aggregates
    return {
      orders: rows.map((row) => new Order({
        orderId: row.orderId,
        userId: row.userId,
        items: row.items.map((i) => new OrderItem(i)),
        status: row.status
      })),
      count
    }
  }

  public async saveOrder(order: Order): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(OrderEntity, {
      orderId: order.orderId,
      userId: order.userId,
      status: order.status,
      items: order.items.map((i) => ({
        itemId: i.itemId,
        name: i.name,
        price: i.price,
        quantity: i.quantity
      }))
    })
    // save to the outbox together if there are domain events (same transaction)
    if (order.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  }

  public async deleteOrder(orderId: string): Promise<void> {
    const manager = this.transactionManager.getManager()
    // cascade: delete child entities first
    await manager.softDelete(OrderItemEntity, { orderId })
    await manager.softDelete(OrderEntity, { orderId })
  }
}
```

### Interface Layer Responsibilities

The Interface layer provides the REST API entry points.

#### Controller

1. Receive the request
2. Call the Command Service or the Query Service
3. Catch errors with `.catch()` → convert into an HTTP exception

The Controller injects the Application Service via NestJS DI and calls it, and never directly
imports an `infrastructure/` implementation (a Repository impl, etc.). If `interface/**/*.ts` of a
domain-bearing BC (a domain with `domain/`) directly imports `infrastructure/`,
`harness/evaluators/rules/interface-no-infrastructure.evaluator.ts` catches it as
`interface-no-infrastructure.forbidden-import` — a cross-cutting-concern technical module with no
`domain/`, like `common/` (e.g. `HealthController` directly referencing `ShutdownState`,
see [graceful-shutdown.md](graceful-shutdown.md)), isn't a target.

```typescript
// interface/order-controller.ts
@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@Authenticated()
@UseInterceptors(LoggingInterceptor)
export class OrderController {
  private readonly logger = new Logger(OrderController.name)

  constructor(
    private readonly orderCommandService: OrderCommandService,
    private readonly orderQueryService: OrderQueryService
  ) {}

  @Get('/orders/:orderId')
  @ApiOperation({ operationId: 'getOrder' })
  @ApiOkResponse({ type: GetOrderResponseBody })
  public async getOrder(
    @Param() param: GetOrderRequestParam
  ): Promise<GetOrderResponseBody> {
    return this.orderQueryService.getOrder(param).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND]
      ])
    })
  }
}
```

### Interface DTO

#### REST DTO = a thin wrapper around an Application object

An Interface DTO wraps the Application layer's Query/Result/Command via `extends`. It adds no separate logic or decorators.

```typescript
// application/query/get-order-query.ts — a single-record lookup Query object
import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetOrderQuery {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string
}

// interface/dto/get-orders-request-querystring.ts
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
export class GetOrdersRequestQuerystring extends GetOrdersQuery {}

// interface/dto/get-order-request-param.ts
import { GetOrderQuery } from '@/order/application/query/get-order-query'
export class GetOrderRequestParam extends GetOrderQuery {}

// interface/dto/get-orders-response-body.ts
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
export class GetOrdersResponseBody extends GetOrdersResult {}
```
