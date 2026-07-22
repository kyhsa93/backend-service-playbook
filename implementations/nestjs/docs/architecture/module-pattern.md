# NestJS Module Pattern

### Domain-Based Module Composition Principle

A NestJS module is organized around a **Bounded Context (domain)**. The criterion for splitting modules is the business domain, not a technical layer (controller, service, repository).

```
src/
  order/                 ← OrderModule — contains every layer of the order domain
    domain/
    application/
    interface/
    infrastructure/
    order-module.ts
  user/                  ← UserModule — contains every layer of the user domain
    domain/
    application/
    interface/
    infrastructure/
    user-module.ts
  payment/               ← PaymentModule — the payment domain
    ...
    payment-module.ts
  common/                ← shared utilities (not a module)
  database/              ← DatabaseModule — TypeORM DataSource, TransactionManager (@Global)
  outbox/                ← OutboxModule (@Global) — OutboxWriter, EventHandlerRegistry, OutboxPoller (DB→SQS), OutboxConsumer (SQS→Handler, long-poll). A single Outbox path shared by every domain, with no per-domain OutboxRelay (see domain-events.md)
  auth/                  ← AuthModule — the shared authentication module
  app-module.ts          ← the root module: composes the domain modules
```

**Principles:**
- **1 Bounded Context = 1 NestJS Module**: split modules by domain — order, user, payment, etc.
- **Each module contains all 4 layers** (domain/application/interface/infrastructure). Don't split modules by layer.
- **Minimize direct dependencies between modules.** If you need another domain's data, `imports` that module and use its `exports`ed service.
- **Split shared infrastructure** (TypeORM DataSource, AuthGuard, etc.) into a separate module, injected wherever a domain module needs it.

### The Root Module — Composing the Domain Modules

```typescript
// app-module.ts
import { Module } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'

import { AuthModule } from '@/auth/auth-module'
import { validateConfig } from '@/config/validation.config'
import { databaseConfig } from '@/config/database.config'
import { jwtConfig } from '@/config/jwt.config'
import { s3Config } from '@/config/s3.config'
import { OrderModule } from '@/order/order-module'
import { PaymentModule } from '@/payment/payment-module'
import { UserModule } from '@/user/user-module'

@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      load: [databaseConfig, jwtConfig, s3Config],
      validate: validateConfig,
    }),
    AuthModule,
    OrderModule,
    UserModule,
    PaymentModule,
    ...(process.env.NODE_ENV === 'prd' ? [] : [DevToolModule])
  ]
})
export class AppModule {}
```

### Dependencies Between Modules — Calling an External Domain via an Adapter

> See [cross-domain.md](cross-domain.md) for the concept and principles of cross-domain calls. This section focuses on module registration.

When you need functionality from another domain, **never inject that domain's Service or Repository directly.** Instead, define an Adapter interface (abstract class) in the Application layer, and write an implementation in the Infrastructure layer that actually calls the external domain module.

**Why:**
- The Application layer doesn't depend on the external domain's concrete Service/Repository types.
- If the external domain's internal structure changes, only the Adapter implementation needs updating.
- In tests, the Adapter can be mocked, allowing unit testing without a dependency on the external domain.

```
[Order domain]                                [User domain]
  application/                                  application/
    adapter/                                      user-service.ts
      user-adapter.ts (abstract class)
    command/
      order-command-service.ts (injects UserAdapter)
  infrastructure/
    user-adapter-impl.ts (calls UserService)  ←imports→  UserModule
```

**Step 1 — Define the Adapter interface in the Application layer**

```typescript
// order/application/adapter/user-adapter.ts — abstract class
export abstract class UserAdapter {
  abstract findUsers(query: {
    readonly take: number
    readonly page: number
    readonly userId?: string
  }): Promise<{ users: { userId: string; name: string }[]; count: number }>
}
```

- Define the Adapter interface in the **shape needed by the calling side (the Order domain)**.
- Don't expose the external domain's full API — define only the methods you need.
- Name query methods following the same `find<Noun>s` pattern as the Repository. For a single-record lookup, use the `take: 1` + `.then(r => r.<noun>s.pop())` pattern.

**Step 2 — Write the Adapter implementation in the Infrastructure layer**

```typescript
// order/infrastructure/user-adapter-impl.ts
import { Injectable } from '@nestjs/common'

import { UserAdapter } from '@/order/application/adapter/user-adapter'
import { UserService } from '@/user/application/user-service'

@Injectable()
export class UserAdapterImpl extends UserAdapter {
  constructor(private readonly userService: UserService) {}

  public async findUsers(query: {
    readonly take: number
    readonly page: number
    readonly userId?: string
  }): Promise<{ users: { userId: string; name: string }[]; count: number }> {
    return this.userService.getUsers(query)
  }
}
```

- The implementation injects and calls the external domain's `exports`ed Service.
- It converts the external domain's response into the shape the Adapter interface defines.

**Step 3 — Use the Adapter in the Application Service**

```typescript
// order/application/command/order-command-service.ts
@Injectable()
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly userAdapter: UserAdapter
  ) {}

  public async createOrderWithUser(command: CreateOrderCommand): Promise<void> {
    const user = await this.userAdapter
      .findUsers({ userId: command.userId, take: 1, page: 0 })
      .then((r) => r.users.pop())
    if (!user) throw new Error(ErrorMessage['사용자를 찾을 수 없습니다.'])

    const order = new Order({ userId: user.userId, items: command.items.map((i) => new OrderItem(i)), status: 'pending' })
    await this.orderRepository.saveOrder(order)
  }
}
```

**Step 4 — Module Registration**

```typescript
// user/user-module.ts — exports UserService
@Module({
  imports: [TypeOrmModule.forFeature([UserEntity])],
  controllers: [UserController],
  providers: [
    UserCommandService,
    UserQueryService,
    { provide: UserRepository, useClass: UserRepositoryImpl },
    { provide: UserQuery, useClass: UserQueryImpl }
  ],
  exports: [UserCommandService, UserQueryService]
})
export class UserModule {}

// order/order-module.ts — imports UserModule + wires the Adapter's DI
@Module({
  imports: [UserModule, TypeOrmModule.forFeature([OrderEntity, OrderItemEntity])],
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: OrderQuery, useClass: OrderQueryImpl },
    { provide: UserAdapter, useClass: UserAdapterImpl }
  ]
})
export class OrderModule {}
```

> **Note**: if a circular dependency between modules occurs (A → B → A), reconsider the design. A circular dependency can be a sign that the Bounded Context boundaries are set up incorrectly. Rather than working around it with `forwardRef()`, re-adjust the domain boundaries or switch to event-based communication.

### Technical Infrastructure Service — Separating Interfaces for Encryption/Decryption, External API Clients, and Similar Concerns

For the principles and detailed examples of the technical infrastructure Service pattern (splitting encryption/decryption, external API clients, etc. into an Application-layer interface + Infrastructure-layer implementation), see the "Technical Service" section of the root [domain-service.md](../../../../docs/architecture/domain-service.md). The file-storage (Presigned URL) example is covered separately in [file-storage.md](file-storage.md).

When registering a Module, bind the interface → implementation in the DI container:

```typescript
// order/order-module.ts
@Module({
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: OrderQuery, useClass: OrderQueryImpl },
    { provide: CryptoService, useClass: CryptoServiceImpl }
  ]
})
export class OrderModule {}
```

### Module Declaration — Minimal, Explicit

```typescript
@Module({
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: OrderQuery, useClass: OrderQueryImpl },
    AuthService
  ]
})
export class OrderModule {}
```

### Environment-Based Conditional Module Loading

```typescript
// app.module.ts
...(process.env.NODE_ENV === 'prd' ? [] : [DevToolModule])
```

### Controller Decorator Pattern (Required Items)

```typescript
@Controller('route-prefix')
@ApiTags('TagName')
@ApiBearerAuth('token')
@UseGuards(AuthGuard)
@UseInterceptors(LoggingInterceptor)
export class OrderController {
  private readonly logger = new Logger(OrderController.name)
  constructor(
    private readonly orderCommandService: OrderCommandService,
    private readonly orderQueryService: OrderQueryService
  ) {}
}
```

- `@ApiTags()`: always used for Swagger grouping
- `@ApiBearerAuth('token')`: always used on a Controller requiring authentication
- `@ApiOperation({ operationId: 'methodName' })`: supports code generation
- `@ApiOperation({ deprecated: true })`: marks an endpoint slated for removal (don't delete immediately — allows a migration period)
- Guards/Interceptors: applied at the class level, not the method level

### @Controller Route Prefix — an Exception Case

```typescript
// applying a prefix to the whole controller at once
@Controller('orders')
export class OrderController {
  @Get()           // → GET /orders
  @Get(':id')      // → GET /orders/:id
}

// the exception case — specify the full path per method
@Controller()
export class OrderController {
  @Get('/orders')          // → GET /orders
  @Get('/orders/:id')      // → GET /orders/:id
}
```
