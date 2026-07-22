# Reference Implementation Template

An example of one complete domain implemented with this architecture. When adding a new domain, copy this template as a starting point.

---

## Directory Structure

```
src/
  config/
    database.config.ts               ← DB config factory
    jwt.config.ts                    ← JWT config factory
    validation.config.ts             ← environment variable validation
  order/
    domain/
      order.ts                       ← Aggregate Root
      order-item.ts                  ← Value Object
      order-cancelled.ts             ← Domain Event
      order-repository.ts            ← Repository interface (abstract class)
      payment-repository.ts          ← Repository interface (abstract class)
    application/
      adapter/
        user-adapter.ts                ← external-domain call interface (abstract class)
      service/
        crypto-service.ts              ← technical infrastructure interface (abstract class)
      command/
        order-command-service.ts       ← Command Service (write)
        cancel-order-command.ts
        create-order-command.ts
        delete-order-command.ts
      query/
        order-query-service.ts         ← Query Service (read)
        order-query.ts                 ← Query interface (abstract class)
        get-order-query.ts
        get-order-result.ts
        get-orders-query.ts
        get-orders-result.ts
    interface/
      order-controller.ts
      order-task-controller.ts       ← Task Controller (optional — when there's an async Task)
      dto/
        cancel-order-request-body.ts
        create-order-request-body.ts
        delete-order-request-param.ts
        get-order-request-param.ts
        get-order-response-body.ts
        get-orders-request-querystring.ts
        get-orders-response-body.ts
    infrastructure/
      entity/
        order.entity.ts              ← TypeORM Entity
        order-item.entity.ts         ← TypeORM Entity
      order-query-impl.ts             ← Query implementation
      order-repository-impl.ts       ← Repository implementation
      payment-repository-impl.ts
      user-adapter-impl.ts           ← external-domain Adapter implementation
      crypto-service-impl.ts          ← technical infrastructure Service implementation
      order-cleanup-scheduler.ts     ← Scheduler (optional — when a Cron is needed)
    order-module.ts
    order-error-message.ts
    order-enum.ts
    order-constant.ts
```

---

## Domain Layer

### Aggregate Root

```typescript
// domain/order.ts — framework-independent
import { generateId } from '@/common/generate-id'
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

  constructor(params: {
    orderId?: string
    userId: string
    items: OrderItem[]
    status: 'pending' | 'paid' | 'cancelled'
  }) {
    if (params.items.length === 0) throw new Error(OrderErrorMessage['An order must have at least one item.'])
    this.orderId = params.orderId ?? generateId()
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
    this._events.push(new OrderCancelled({
      orderId: this.orderId,
      reason,
      cancelledAt: new Date()
    }))
  }

  public getTotalAmount(): number {
    return this.items.reduce((sum, item) => sum + item.price * item.quantity, 0)
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

### Value Object

```typescript
// domain/order-item.ts — an immutable object
import { OrderErrorMessage } from '@/order/order-error-message'

export class OrderItem {
  public readonly itemId: number
  public readonly name: string
  public readonly price: number
  public readonly quantity: number

  constructor(params: { itemId: number; name: string; price: number; quantity: number }) {
    if (params.price <= 0) throw new Error(OrderErrorMessage['The product price must be greater than 0.'])
    if (params.quantity <= 0) throw new Error(OrderErrorMessage['The quantity must be greater than 0.'])
    this.itemId = params.itemId
    this.name = params.name
    this.price = params.price
    this.quantity = params.quantity
  }

  public equals(other: OrderItem): boolean {
    return this.itemId === other.itemId
      && this.name === other.name
      && this.price === other.price
      && this.quantity === other.quantity
  }
}
```

### Domain Event

```typescript
// domain/order-cancelled.ts
export class OrderCancelled {
  public readonly orderId: string
  public readonly reason: string
  public readonly cancelledAt: Date

  constructor(params: { orderId: string; reason: string; cancelledAt: Date }) {
    this.orderId = params.orderId
    this.reason = params.reason
    this.cancelledAt = params.cancelledAt
  }
}
```

### Repository Interface

```typescript
// domain/order-repository.ts — abstract class
import { Order } from '@/order/domain/order'

export abstract class OrderRepository {
  abstract findOrders(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
    readonly userId?: string
    readonly status?: string[]
  }): Promise<{ orders: Order[]; count: number }>

  abstract saveOrder(order: Order): Promise<void>

  abstract deleteOrder(orderId: string): Promise<void>
}
```

```typescript
// domain/payment-repository.ts — abstract class
export abstract class PaymentRepository {
  abstract findPaymentMethods(query: {
    readonly take: number
    readonly page: number
    readonly orderId?: string
  }): Promise<{ paymentMethods: PaymentMethod[]; count: number }>

  abstract deletePaymentMethods(orderId: string): Promise<void>
}
```

---

## Application Layer

### Command Service

```typescript
// application/command/order-command-service.ts
import { Injectable } from '@nestjs/common'

import { TransactionManager } from '@/database/transaction-manager'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { CreateOrderCommand } from '@/order/application/command/create-order-command'
import { DeleteOrderCommand } from '@/order/application/command/delete-order-command'
import { Order } from '@/order/domain/order'
import { OrderItem } from '@/order/domain/order-item'
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

  public async createOrder(command: CreateOrderCommand): Promise<void> {
    // create the Aggregate (invariants are validated in the constructor)
    const order = new Order({
      userId: command.userId,
      items: command.items.map((i) => new OrderItem(i)),
      status: 'pending'
    })
    await this.orderRepository.saveOrder(order)
  }

  // update — fetch → call the Aggregate's domain method → save via a transaction
  public async cancelOrder(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(ErrorMessage['Order not found.'])

    // business rules are validated inside the Aggregate
    order.cancel(command.reason)

    // Repository.saveOrder() saves the Aggregate + outbox together internally
    await this.transactionManager.run(async () => {
      await this.paymentRepository.deletePaymentMethods(order.orderId)
      await this.orderRepository.saveOrder(order)
    })
  }

  public async deleteOrder(command: DeleteOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(ErrorMessage['Order not found.'])

    await this.orderRepository.deleteOrder(command.orderId)
  }
}
```

### Query Interface

```typescript
// application/query/order-query.ts — abstract class
import { GetOrderResult } from '@/order/application/query/get-order-result'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'

export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(param: { orderId: string }): Promise<GetOrderResult>
}
```

### Query Service

```typescript
// application/query/order-query-service.ts
import { Injectable } from '@nestjs/common'

import { GetOrderResult } from '@/order/application/query/get-order-result'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
import { OrderQuery } from '@/order/application/query/order-query'

@Injectable()
export class OrderQueryService {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }

  public async getOrder(param: { orderId: string }): Promise<GetOrderResult> {
    return this.orderQuery.getOrder(param)
  }
}
```

### Command

```typescript
// application/command/cancel-order-command.ts
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsOptional, IsString, Min, MinLength } from 'class-validator'

export class CancelOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string

  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly reason: string

  @ApiPropertyOptional()
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly refundAmount?: number

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}

// application/command/create-order-command.ts
import { ApiProperty } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsArray, IsInt, IsString, Min, MinLength } from 'class-validator'

export class CreateOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly userId: string

  @ApiProperty()
  @IsArray()
  public readonly items: { itemId: number; name: string; price: number; quantity: number }[]

  constructor(command: CreateOrderCommand) {
    Object.assign(this, command)
  }
}
```

### Query / Result

```typescript
// application/query/get-orders-query.ts
import { ApiProperty, ApiPropertyOptional } from '@nestjs/swagger'
import { Type } from 'class-transformer'
import { IsInt, IsOptional, IsString, Max, Min } from 'class-validator'

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

// application/query/get-orders-result.ts
import { ApiProperty } from '@nestjs/swagger'

export class OrderSummaryItem {
  @ApiProperty()
  public readonly orderId: string

  @ApiProperty({ nullable: true, type: String })
  public readonly description: string | null

  @ApiProperty()
  public readonly status: string
}

export class GetOrdersResult {
  @ApiProperty({ type: [OrderSummaryItem] })
  public readonly orders: OrderSummaryItem[]

  @ApiProperty()
  public readonly totalCount: number
}
```

---

## Infrastructure Layer

### Query Implementation

```typescript
// infrastructure/order-query-impl.ts
import { Injectable } from '@nestjs/common'
import { InjectRepository } from '@nestjs/typeorm'
import { Repository } from 'typeorm'

import { GetOrderResult } from '@/order/application/query/get-order-result'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
import { OrderQuery } from '@/order/application/query/order-query'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'

@Injectable()
export class OrderQueryImpl extends OrderQuery {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    @InjectRepository(OrderItemEntity) private readonly orderItemRepo: Repository<OrderItemEntity>
  ) {
    super()
  }

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    const qb = this.orderRepo.createQueryBuilder('order')
      .orderBy('order.orderId', 'DESC')
      .take(query.take)
      .skip(query.page * query.take)

    if (query.status?.length) qb.andWhere('order.status IN (:...status)', { status: query.status })

    const [rows, count] = await qb.getManyAndCount()

    return {
      orders: rows.map((o) => ({
        orderId: o.orderId,
        description: null,
        status: o.status
      })),
      totalCount: count
    }
  }

  public async getOrder(param: { orderId: string }): Promise<GetOrderResult> {
    const row = await this.orderRepo.createQueryBuilder('order')
      .leftJoinAndSelect('order.items', 'item')
      .where('order.orderId = :orderId', { orderId: param.orderId })
      .getOne()
    if (!row) throw new Error(ErrorMessage['Order not found.'])

    const totalAmount = row.items.reduce((sum, item) => sum + item.price * item.quantity, 0)
    return {
      orderId: row.orderId,
      status: row.status,
      totalAmount
    }
  }
}
```

### Repository Implementation

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
    // cascade soft delete: child entities first
    await manager.softDelete(OrderItemEntity, { orderId })
    await manager.softDelete(OrderEntity, { orderId })
  }
}
```

---

## Interface Layer

### Controller

```typescript
// interface/order-controller.ts
import {
  BadRequestException, Body, Controller, Delete, Get, HttpCode, Logger,
  NotFoundException, Param, Post, Query, UseGuards, UseInterceptors
} from '@nestjs/common'
import {
  ApiBearerAuth, ApiCreatedResponse, ApiNoContentResponse,
  ApiOkResponse, ApiOperation, ApiTags
} from '@nestjs/swagger'

import { AuthGuard } from '@/auth/auth.guard'
import { generateErrorResponse } from '@/common/generate-error-response'
import { LoggingInterceptor } from '@/common/logging.interceptor'
import { OrderCommandService } from '@/order/application/command/order-command-service'
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
import { CreateOrderCommand } from '@/order/application/command/create-order-command'
import { OrderQueryService } from '@/order/application/query/order-query-service'
import { CancelOrderRequestBody } from '@/order/interface/dto/cancel-order-request-body'
import { CreateOrderRequestBody } from '@/order/interface/dto/create-order-request-body'
import { DeleteOrderRequestParam } from '@/order/interface/dto/delete-order-request-param'
import { GetOrderRequestParam } from '@/order/interface/dto/get-order-request-param'
import { GetOrderResponseBody } from '@/order/interface/dto/get-order-response-body'
import { GetOrdersRequestQuerystring } from '@/order/interface/dto/get-orders-request-querystring'
import { GetOrdersResponseBody } from '@/order/interface/dto/get-orders-response-body'
import { OrderErrorCode as ErrorCode } from '@/order/order-error-code'
import { OrderErrorMessage } from '@/order/order-error-message'

@Controller()
@ApiBearerAuth('token')
@ApiTags('Order')
@UseGuards(AuthGuard)
@UseInterceptors(LoggingInterceptor)
export class OrderController {
  private readonly logger = new Logger(OrderController.name)

  constructor(
    private readonly orderCommandService: OrderCommandService,
    private readonly orderQueryService: OrderQueryService
  ) {}

  @Get('/orders')
  @ApiOperation({ operationId: 'getOrders' })
  @ApiOkResponse({ type: GetOrdersResponseBody })
  public async getOrders(
    @Query() querystring: GetOrdersRequestQuerystring
  ): Promise<GetOrdersResponseBody> {
    return this.orderQueryService.getOrders(querystring).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [])
    })
  }

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

  @Post('/orders')
  @ApiOperation({ operationId: 'createOrder' })
  @ApiCreatedResponse()
  public async createOrder(
    @Body() body: CreateOrderRequestBody
  ): Promise<void> {
    return this.orderCommandService.createOrder(new CreateOrderCommand(body)).catch((error) => {
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
    return this.orderCommandService.cancelOrder(new CancelOrderCommand({ ...body, orderId })).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND],
        [OrderErrorMessage['The order is already cancelled.'], BadRequestException, ErrorCode.ORDER_ALREADY_CANCELLED],
        [OrderErrorMessage['A paid order cannot be cancelled.'], BadRequestException, ErrorCode.ORDER_PAID_NOT_CANCELLABLE]
      ])
    })
  }

  @Delete('/orders/:orderId')
  @HttpCode(204)
  @ApiOperation({ operationId: 'deleteOrder' })
  @ApiNoContentResponse()
  public async deleteOrder(
    @Param() param: DeleteOrderRequestParam
  ): Promise<void> {
    return this.orderCommandService.deleteOrder(param).catch((error) => {
      this.logger.error(error)
      throw generateErrorResponse(error.message, [
        [OrderErrorMessage['Order not found.'], NotFoundException, ErrorCode.ORDER_NOT_FOUND]
      ])
    })
  }
}
```

### Query (Application layer — single-record lookup)

```typescript
// application/query/get-order-query.ts
import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class GetOrderQuery {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string
}

// application/command/delete-order-command.ts
import { ApiProperty } from '@nestjs/swagger'
import { IsString, MinLength } from 'class-validator'

export class DeleteOrderCommand {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  public readonly orderId: string
}
```

### Result (Application layer)

```typescript
// application/query/get-order-result.ts
import { ApiProperty } from '@nestjs/swagger'

export class GetOrderResult {
  @ApiProperty()
  public readonly orderId: string

  @ApiProperty()
  public readonly status: string

  @ApiProperty()
  public readonly totalAmount: number
}
```

### Interface DTOs

```typescript
// interface/dto/get-orders-request-querystring.ts
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
export class GetOrdersRequestQuerystring extends GetOrdersQuery {}

// interface/dto/get-orders-response-body.ts
import { GetOrdersResult } from '@/order/application/query/get-orders-result'
export class GetOrdersResponseBody extends GetOrdersResult {}

// interface/dto/get-order-request-param.ts
import { GetOrderQuery } from '@/order/application/query/get-order-query'
export class GetOrderRequestParam extends GetOrderQuery {}

// interface/dto/get-order-response-body.ts
import { GetOrderResult } from '@/order/application/query/get-order-result'
export class GetOrderResponseBody extends GetOrderResult {}

// interface/dto/cancel-order-request-body.ts
import { CancelOrderCommand } from '@/order/application/command/cancel-order-command'
export class CancelOrderRequestBody extends CancelOrderCommand {}

// interface/dto/create-order-request-body.ts
import { CreateOrderCommand } from '@/order/application/command/create-order-command'
export class CreateOrderRequestBody extends CreateOrderCommand {}

// interface/dto/delete-order-request-param.ts
import { DeleteOrderCommand } from '@/order/application/command/delete-order-command'
export class DeleteOrderRequestParam extends DeleteOrderCommand {}
```

### Task Controller (optional — when there's an async Task)

Subscribes via `@TaskConsumer` to a Task enqueued by the Scheduler or another service, and executes the Command. See [scheduling.md](architecture/scheduling.md) for the detailed pattern.

```typescript
// interface/order-task-controller.ts
import { Injectable, Logger } from '@nestjs/common'

import { OrderCommandService } from '@/order/application/command/order-command-service'
import { TaskConsumer } from '@/task-queue/task-consumer.decorator'

@Injectable()
export class OrderTaskController {
  private readonly logger = new Logger(OrderTaskController.name)

  constructor(private readonly orderCommandService: OrderCommandService) {}

  // an inherently idempotent Task — no options needed
  @TaskConsumer('order.cleanup-expired')
  public async cleanupExpired(): Promise<void> {
    const count = await this.orderCommandService.cleanupExpiredOrders()
    this.logger.log({ message: 'Expired orders cleaned up', cleaned_count: count })
  }

  // a Task that needs protection against per-entity duplicate execution — via the
  // idempotencyKey option, the framework leaves a ledger entry in TaskExecutionLog for auto-skip
  // (see scheduling.md for the detailed pattern)
  @TaskConsumer('order.archive', {
    idempotencyKey: (payload: { orderId: string }) => `order.archive-${payload.orderId}`
  })
  public async archive(payload: { orderId: string }): Promise<void> {
    await this.orderCommandService.archiveOrder(payload.orderId)
  }
}
```

### Scheduler (optional — when a Cron is needed)

```typescript
// infrastructure/order-cleanup-scheduler.ts
import { Injectable, Logger } from '@nestjs/common'
import { Cron, CronExpression } from '@nestjs/schedule'

import { TaskQueue } from '@/task-queue/task-queue'

@Injectable()
export class OrderCleanupScheduler {
  private readonly logger = new Logger(OrderCleanupScheduler.name)

  constructor(private readonly taskQueue: TaskQueue) {}

  @Cron(CronExpression.EVERY_DAY_AT_MIDNIGHT)
  public async enqueueDailyCleanup(): Promise<void> {
    const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
    try {
      await this.taskQueue.enqueue(
        'order.cleanup-expired',
        {},
        { groupId: 'order.cleanup', deduplicationId: dedupId }
      )
    } catch (error) {
      // @nestjs/schedule swallows exceptions from Cron handlers, so explicit logging is required
      this.logger.error({ message: 'Failed to enqueue', dedup_id: dedupId, error })
    }
  }
}
```

---

## Module

```typescript
// order-module.ts
import { Module } from '@nestjs/common'

import { TypeOrmModule } from '@nestjs/typeorm'

import { AuthService } from '@/auth/auth-service'
import { OrderCommandService } from '@/order/application/command/order-command-service'
import { OrderQuery } from '@/order/application/query/order-query'
import { OrderQueryService } from '@/order/application/query/order-query-service'
import { CryptoService } from '@/order/application/service/crypto-service'
import { OrderRepository } from '@/order/domain/order-repository'
import { PaymentRepository } from '@/order/domain/payment-repository'
import { CryptoServiceImpl } from '@/order/infrastructure/crypto-service-impl'
import { OrderEntity } from '@/order/infrastructure/entity/order.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'
import { OrderQueryImpl } from '@/order/infrastructure/order-query-impl'
import { OrderRepositoryImpl } from '@/order/infrastructure/order-repository-impl'
import { PaymentRepositoryImpl } from '@/order/infrastructure/payment-repository-impl'
import { OrderCleanupScheduler } from '@/order/infrastructure/order-cleanup-scheduler'
import { OrderController } from '@/order/interface/order-controller'
import { OrderTaskController } from '@/order/interface/order-task-controller'

@Module({
  imports: [TypeOrmModule.forFeature([OrderEntity, OrderItemEntity])],
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    OrderTaskController,        // Task Controller — has @TaskConsumer methods
    OrderCleanupScheduler,      // Scheduler — enqueues a Task via Cron
    { provide: OrderQuery, useClass: OrderQueryImpl },
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl },
    { provide: CryptoService, useClass: CryptoServiceImpl },
    AuthService
  ]
})
export class OrderModule {}
```

---

## Error Message

```typescript
// order-error-message.ts
export enum OrderErrorMessage {
  'Order not found.' = 'Order not found.',
  'The order is already cancelled.' = 'The order is already cancelled.',
  'A paid order cannot be cancelled.' = 'A paid order cannot be cancelled.',
  'Payment information could not be found.' = 'Payment information could not be found.',
  'An order must have at least one item.' = 'An order must have at least one item.',
  'The product price must be greater than 0.' = 'The product price must be greater than 0.',
  'The quantity must be greater than 0.' = 'The quantity must be greater than 0.',
}
```
