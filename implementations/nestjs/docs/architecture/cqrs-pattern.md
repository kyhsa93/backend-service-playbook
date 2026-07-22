# Applying the @nestjs/cqrs Pattern

Using the `@nestjs/cqrs` package splits the Application layer's Service into a Command Handler / Query Handler / Event Handler. The existing architecture's principles (Domain layer independence, encapsulating business rules in the Aggregate, the Repository pattern) remain the same.

### Directory Structure Changes

Applying `@nestjs/cqrs` changes the structure under `application/`:

```
src/
  <domain>/
    domain/                              # unchanged
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <aggregate>-repository.ts
    application/
      command/
        <verb>-<noun>-command.ts          # Command object
        <verb>-<noun>-command-handler.ts   # CommandHandler (the old Service's write logic)
      query/
        <domain>-query.ts                 # Query interface (abstract class) — unchanged
        <verb>-<noun>-query.ts            # Query object
        <verb>-<noun>-query-handler.ts    # QueryHandler (the old Service's read logic)
        <verb>-<noun>-result.ts
      event/
        <domain-event>-handler.ts         # EventHandler (event follow-up processing)
    interface/                           # change: uses CommandBus/QueryBus instead of a Service
      <domain>-controller.ts
      dto/
    infrastructure/                      # unchanged
      <aggregate>-repository-impl.ts
    <domain>-module.ts                   # change: CqrsModule import + Handler registration
```

### Dependency Direction Change

```
Interface (Controller) → CommandBus / QueryBus → Command/Query Handler → Domain (Aggregate, Repository)
                                                                              ↑
                                              EventHandler ←── SQS Consumer   Infrastructure (Repository implementation)
```

- The Controller calls `CommandBus.execute()` / `QueryBus.execute()` instead of a Service.
- CommandHandler / QueryHandler replace the role of the old Application Service.
- The EventHandler doesn't use `@nestjs/cqrs`'s in-process EventBus. The Domain Event is delivered via the Outbox → SQS → `EventConsumer` path, with the handler registered via the `@HandleEvent` decorator (see [domain-events.md](domain-events.md)).

### Command and CommandHandler

A Command is a data object representing a write request. The CommandHandler processes it.

```typescript
// application/command/cancel-order-command.ts — the Command object (same as before)
export class CancelOrderCommand {
  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly orderId: string

  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly reason: string

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}
```

```typescript
// application/command/cancel-order-command-handler.ts — the CommandHandler
import { CommandHandler, ICommandHandler } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { OrderRepository } from '@/order/domain/order-repository'
import { PaymentRepository } from '@/order/domain/payment-repository'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@CommandHandler(CancelOrderCommand)
export class CancelOrderCommandHandler implements ICommandHandler<CancelOrderCommand> {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly paymentRepository: PaymentRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelOrderCommand): Promise<void> {
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

### Query and QueryHandler

A Query is a data object representing a read request. The QueryHandler processes it.

```typescript
// application/query/get-orders-query.ts — the Query object (same as before)
export class GetOrdersQuery {
  @ApiPropertyOptional({ type: [String] })
  @IsOptional()
  @IsString({ each: true })
  public readonly status?: string[]

  @ApiProperty({ minimum: 0, default: 0 })
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly page: number

  @ApiProperty({ minimum: 1, maximum: 100, default: 20 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  public readonly take: number
}
```

```typescript
// application/query/get-orders-query-handler.ts — the QueryHandler
import { IQueryHandler, QueryHandler } from '@nestjs/cqrs'

import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
import { OrderQuery } from '@/order/application/query/order-query'

@QueryHandler(GetOrdersQuery)
export class GetOrdersQueryHandler implements IQueryHandler<GetOrdersQuery> {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async execute(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

### The QueryHandler and the Read Model

The QueryHandler **never uses the Domain layer's `OrderRepository`.** The Repository is a write-only interface for reconstructing the Aggregate. Instead, it injects and uses the **`OrderQuery` interface (abstract class)** defined in the Application layer.

```
Read-only interface (Read Model)
  application/query/order-query.ts        ← OrderQuery (abstract class) — the interface the QueryHandler injects
  infrastructure/order-query-impl.ts      ← OrderQueryImpl — queries the DB directly, no need to reconstruct the Aggregate

Write-only interface
  domain/order-repository.ts              ← OrderRepository (abstract class) — used only by the CommandHandler
  infrastructure/order-repository-impl.ts ← OrderRepositoryImpl — reconstructs the Aggregate + saves to the Outbox
```

`OrderQuery` and `OrderQueryImpl` play the role of CQRS's **Read Model**. The Service-based `OrderQueryService` and `@nestjs/cqrs`'s `GetOrdersQueryHandler` share the same `OrderQuery` interface. Whichever approach you choose, the same read/write separation principle applies.

### EventHandler

Reacts to a Domain Event and performs follow-up processing. Multiple EventHandlers can be registered for a single Event.

```typescript
// application/event/order-cancelled-handler.ts
import { Injectable, Logger } from '@nestjs/common'

import { HandleEvent } from '@/outbox/event-handler-registry'

@Injectable()
export class OrderCancelledHandler {
  private readonly logger = new Logger(OrderCancelledHandler.name)

  @HandleEvent('OrderCancelled')
  public async handle(event: { orderId: string; reason: string }): Promise<void> {
    // follow-up processing: send a notification, log an audit record, restock, etc.
    this.logger.log({ message: 'Order cancelled', order_id: event.orderId, reason: event.reason })
  }
}
```

### Controller — Using CommandBus / QueryBus

The Controller injects and uses the CommandBus and QueryBus instead of a Service.

```typescript
// interface/order-controller.ts
import { CommandBus, QueryBus } from '@nestjs/cqrs'

@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@UseGuards(AuthGuard)
@UseInterceptors(LoggingInterceptor)
export class OrderController {
  private readonly logger = new Logger(OrderController.name)

  constructor(
    private readonly commandBus: CommandBus,
    private readonly queryBus: QueryBus
  ) {}

  @Get('/orders')
  @ApiOperation({ operationId: 'getOrders' })
  @ApiOkResponse({ type: GetOrdersResponseBody })
  public async getOrders(
    @Query() querystring: GetOrdersRequestQuerystring
  ): Promise<GetOrdersResponseBody> {
    return this.queryBus.execute(new GetOrdersQuery(querystring)).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [])
    })
  }

  @Post('/orders/:orderId/cancel')
  @HttpCode(204)
  @ApiOperation({ operationId: 'cancelOrder' })
  @ApiNoContentResponse()
  public async cancelOrder(
    @Param('orderId') orderId: string,
    @Body() body: CancelOrderRequestBody
  ): Promise<void> {
    return this.commandBus.execute(new CancelOrderCommand({ ...body, orderId })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
        [OrderErrorMessage['The order is already cancelled.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED],
        [OrderErrorMessage['A paid order cannot be cancelled.'], BadRequestException, ErrorCode.ORDER_PAID_NOT_CANCELLABLE]
      ])
    })
  }
}
```

### Module — Registering CqrsModule

```typescript
// order-module.ts
import { Module } from '@nestjs/common'
import { CqrsModule } from '@nestjs/cqrs'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelOrderCommandHandler } from '@/order/application/command/cancel-order-command-handler'
import { CreateOrderCommandHandler } from '@/order/application/command/create-order-command-handler'
import { OrderCancelledHandler } from '@/order/application/event/order-cancelled-handler'
import { GetOrderQueryHandler } from '@/order/application/query/get-order-query-handler'
import { GetOrdersQueryHandler } from '@/order/application/query/get-orders-query-handler'
import { OrderQuery } from '@/order/application/query/order-query'
import { OrderRepository } from '@/order/domain/order-repository'
import { PaymentRepository } from '@/order/domain/payment-repository'
import { OrderQueryImpl } from '@/order/infrastructure/order-query-impl'
import { OrderRepositoryImpl } from '@/order/infrastructure/order-repository-impl'
import { PaymentRepositoryImpl } from '@/order/infrastructure/payment-repository-impl'
import { OrderController } from '@/order/interface/order-controller'

@Module({
  imports: [CqrsModule, TypeOrmModule.forFeature([OrderEntity, OrderItemEntity])],
  controllers: [OrderController],
  providers: [
    // Command Handlers
    CancelOrderCommandHandler,
    CreateOrderCommandHandler,
    // Query Handlers
    GetOrderQueryHandler,
    GetOrdersQueryHandler,
    // Event Handlers
    OrderCancelledHandler,
    // Repositories
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl },
    // Query implementation
    { provide: OrderQuery, useClass: OrderQueryImpl }
  ]
})
export class OrderModule {}
```

### Summary of Differences from the Old Service Approach

| Item | Service approach | @nestjs/cqrs approach |
|------|-------------|-------------------|
| Application layer | Split into `CommandService` + `QueryService` | A separate Handler file per Command/Query |
| Controller dependency | Injects `OrderCommandService` + `OrderQueryService` | Injects `CommandBus`, `QueryBus` |
| Event handling | Outbox + SQS + `@HandleEvent` handler | Outbox + SQS + `@HandleEvent` handler (same) |
| Module registration | Register `CommandService`, `QueryService`, `{ provide: Query, useClass: QueryImpl }` | Register each Handler individually |
| Read/write separation | Command Service(`OrderRepository`) / Query Service(`OrderQuery`) | CommandHandler(`OrderRepository`) / QueryHandler(`OrderQuery`) |

### Selection Criteria

- **The Service approach is recommended**: for simple domains with few use cases and no need for event-based processing
- **The @nestjs/cqrs approach is recommended**: for complex domains with many use cases, a need for read/write model separation, or Domain Event-based follow-up processing

> The two approaches can be mixed per domain within a single project. It's common to apply CQRS to the Core Domain and the Service approach to Supporting/Generic Subdomains.
