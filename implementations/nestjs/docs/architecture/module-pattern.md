# NestJS 모듈 패턴

### 도메인 기준 모듈 구성 원칙

NestJS 모듈은 **Bounded Context(도메인)** 단위로 구성한다. 기술 레이어(controller, service, repository)가 아닌 비즈니스 도메인이 모듈 분리의 기준이다.

```
src/
  order/                 ← OrderModule — 주문 도메인의 모든 레이어를 포함
    domain/
    application/
    interface/
    infrastructure/
    order-module.ts
  user/                  ← UserModule — 사용자 도메인의 모든 레이어를 포함
    domain/
    application/
    interface/
    infrastructure/
    user-module.ts
  payment/               ← PaymentModule — 결제 도메인
    ...
    payment-module.ts
  common/                ← 공유 유틸 (모듈 아님)
  database/              ← DatabaseModule — TypeORM DataSource, TransactionManager (@Global)
  outbox/                ← OutboxModule (@Global) — OutboxWriter, EventHandlerRegistry, OutboxPoller(DB→SQS), OutboxConsumer(SQS→Handler, long-poll). 모든 도메인이 공유하는 단일 Outbox 경로이며 도메인별 OutboxRelay는 없다(domain-events.md 참고)
  auth/                  ← AuthModule — 인증 공유 모듈
  app-module.ts          ← 루트 모듈: 도메인 모듈 조합
```

**원칙:**
- **1 Bounded Context = 1 NestJS Module**: 주문, 사용자, 결제 등 도메인 단위로 모듈을 나눈다.
- **모듈 내에 4개 레이어(domain/application/interface/infrastructure)를 포함**한다. 레이어별로 모듈을 나누지 않는다.
- **모듈 간 직접 의존을 최소화**한다. 다른 도메인의 데이터가 필요하면 해당 모듈을 `imports`하고 `exports`된 서비스를 사용한다.
- **공유 인프라**(TypeORM DataSource, AuthGuard 등)는 별도 모듈로 분리하여 필요한 도메인 모듈에서 주입받는다.

### 루트 모듈 — 도메인 모듈 조합

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

### 모듈 간 의존 — Adapter를 통한 외부 도메인 호출

> 크로스 도메인 호출의 개념과 원칙은 [cross-domain.md](cross-domain.md)를 참조한다. 이 섹션에서는 모듈 등록 중심으로 설명한다.

다른 도메인의 기능이 필요할 때, **해당 도메인의 Service나 Repository를 직접 주입하지 않는다.** 대신 Application 레이어에 Adapter 인터페이스(abstract class)를 정의하고, Infrastructure 레이어에서 실제 외부 도메인 모듈을 호출하는 구현체를 작성한다.

**이유:**
- Application 레이어가 외부 도메인의 구체적인 Service/Repository 타입에 의존하지 않는다.
- 외부 도메인의 내부 구조가 변경되어도 Adapter 구현체만 수정하면 된다.
- 테스트 시 Adapter를 mock하여 외부 도메인 의존 없이 단위 테스트할 수 있다.

```
[Order 도메인]                                [User 도메인]
  application/                                  application/
    adapter/                                      user-service.ts
      user-adapter.ts (abstract class)
    command/
      order-command-service.ts (UserAdapter 주입)
  infrastructure/
    user-adapter-impl.ts (UserService 호출)  ←imports→  UserModule
```

**Step 1 — Application 레이어에 Adapter 인터페이스 정의**

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

- Adapter 인터페이스는 **호출하는 쪽(Order 도메인)이 필요로 하는 형태**로 정의한다.
- 외부 도메인의 전체 API를 노출하지 않고, 필요한 메서드만 정의한다.
- 조회 메서드 네이밍은 Repository와 동일하게 `find<Noun>s` 패턴을 따른다. 단건 조회 시 `take: 1` + `.then(r => r.<noun>s.pop())` 패턴을 사용한다.

**Step 2 — Infrastructure 레이어에 Adapter 구현체 작성**

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

- 구현체에서 외부 도메인의 `exports`된 Service를 주입받아 호출한다.
- 외부 도메인의 응답을 Adapter 인터페이스가 정의한 형태로 변환한다.

**Step 3 — Application Service에서 Adapter 사용**

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

**Step 4 — Module 등록**

```typescript
// user/user-module.ts — UserService를 exports
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

// order/order-module.ts — UserModule imports + Adapter DI 연결
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

> **주의**: 모듈 간 순환 의존(A → B → A)이 발생하면 설계를 재검토한다. 순환 의존은 Bounded Context 경계가 잘못 설정되었다는 신호일 수 있다. `forwardRef()`로 우회하기보다 도메인 경계를 재조정하거나, 이벤트 기반 통신으로 전환한다.

### 기술 인프라 Service — 암복호화·외부 API 클라이언트 등의 인터페이스 분리

기술 인프라 Service 패턴(암복호화, 외부 API 클라이언트 등을 Application 레이어 인터페이스 + Infrastructure 레이어 구현체로 분리)의 원칙과 상세 예시는 root [domain-service.md](../../../../docs/architecture/domain-service.md)의 "Technical Service" 섹션을 참조한다. 파일 스토리지(Presigned URL) 예시는 [file-storage.md](file-storage.md)에 별도로 정리했다.

Module 등록 시에는 인터페이스 → 구현체를 DI 컨테이너에 바인딩한다:

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

### 모듈 선언 — 최소화, 명시적

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

### 환경 기반 조건부 모듈 로딩

```typescript
// app.module.ts
...(process.env.NODE_ENV === 'prd' ? [] : [DevToolModule])
```

### Controller 데코레이터 패턴 (필수 항목들)

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

- `@ApiTags()`: Swagger 그룹핑을 위해 항상 사용
- `@ApiBearerAuth('token')`: 인증 필요 컨트롤러에 항상 사용
- `@ApiOperation({ operationId: 'methodName' })`: 코드 제너레이션 지원
- `@ApiOperation({ deprecated: true })`: 사용 중단 예정 엔드포인트 표시 (즉시 삭제하지 않고 마이그레이션 기간 확보)
- 가드/인터셉터: 메서드 레벨이 아닌 클래스 레벨에 적용

### @Controller 라우트 접두사 — 예외 케이스

```typescript
// 접두사를 컨트롤러에 일괄 적용하는 경우
@Controller('orders')
export class OrderController {
  @Get()           // → GET /orders
  @Get(':id')      // → GET /orders/:id
}

// 예외 케이스 — 메서드별로 전체 경로를 명시
@Controller()
export class OrderController {
  @Get('/orders')          // → GET /orders
  @Get('/orders/:id')      // → GET /orders/:id
}
```
