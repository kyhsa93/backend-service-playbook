# CQRS 패턴

CQRS(Command Query Responsibility Segregation)는 **쓰기(Command)와 읽기(Query)의 책임을 분리**하는 패턴이다.
기존 아키텍처의 원칙(Domain 레이어 무의존, Aggregate 비즈니스 규칙 캡슐화, Repository 패턴)은 동일하게 유지한다.

> 기본 아키텍처([layer-architecture.md](layer-architecture.md))에서 Application Service를 Command Service / Query Service로 분리하는 것도 CQRS의 경량 적용이다. 이 문서는 **Handler 기반 CQRS** — Command Bus / Query Bus를 도입해 각 유스케이스를 독립 Handler 클래스로 분리하는 패턴을 설명한다.

---

### 적용 기준

| 상황 | 권장 |
|---|---|
| 유스케이스가 많아 Service 클래스가 비대해질 때 | Handler 기반 CQRS 적용 |
| 쓰기와 읽기 모델을 완전히 다른 저장소로 분리할 때 | Handler 기반 CQRS 적용 |
| 유스케이스가 적고 Service 클래스가 단순할 때 | 기본 아키텍처(Service 분리)로 충분 |

---

### 디렉토리 구조

```
src/
  <domain>/
    domain/                              # 변경 없음
      <aggregate-root>.ts
      <domain-event>.ts
      <aggregate>-repository.ts
    application/
      command/
        <verb>-<noun>-command.ts          # Command 객체 (입력)
        <verb>-<noun>-command-handler.ts  # CommandHandler (쓰기 로직)
      query/
        <domain>-query.ts                 # Query 인터페이스 (abstract class) — 변경 없음
        <verb>-<noun>-query.ts            # Query 객체 (입력)
        <verb>-<noun>-query-handler.ts    # QueryHandler (읽기 로직)
        <verb>-<noun>-result.ts           # 결과 객체
      event/
        <domain-event>-handler.ts         # EventHandler (이벤트 후속 처리)
    interface/
      <domain>-controller.ts             # CommandBus / QueryBus 호출
```

---

### Command와 CommandHandler

Command는 쓰기 요청을 나타내는 **불변 데이터 객체**다. CommandHandler가 이를 처리한다.

```typescript
// application/command/cancel-order-command.ts
export class CancelOrderCommand {
  public readonly orderId: string
  public readonly reason: string

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}
```

```typescript
// application/command/cancel-order-command-handler.ts
export class CancelOrderCommandHandler {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error('주문을 찾을 수 없습니다.')

    order.cancel(command.reason)

    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)
    })
  }
}
```

---

### Query와 QueryHandler

Query는 읽기 요청을 나타내는 데이터 객체다. QueryHandler가 **읽기 전용 모델**(Query 인터페이스)을 통해 처리한다.

```typescript
// application/query/get-orders-query.ts
export class GetOrdersQuery {
  public readonly take: number
  public readonly page: number
  public readonly status?: string[]
}
```

```typescript
// application/query/get-orders-query-handler.ts
export class GetOrdersQueryHandler {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async execute(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

QueryHandler는 `OrderRepository`(쓰기 모델)가 아닌 `OrderQuery`(읽기 전용 인터페이스)를 사용한다. Aggregate 복원 없이 DB에서 직접 조회한다.

---

### Interface 레이어 — Bus 호출

Controller는 Service 대신 **CommandBus / QueryBus**를 통해 적절한 Handler로 요청을 라우팅한다.

```typescript
// interface/<domain>-controller.ts (개념)
public async cancelOrder(param: CancelOrderRequestParam): Promise<void> {
  return commandBus.execute(new CancelOrderCommand(param))
    .catch((error) => { throw convertToHttpError(error) })
}

public async getOrders(query: GetOrdersRequestQuerystring): Promise<GetOrdersResponseBody> {
  return queryBus.execute(new GetOrdersQuery(query))
    .catch((error) => { throw convertToHttpError(error) })
}
```

---

### EventHandler

Domain Event는 in-process 이벤트 버스를 사용하지 않는다. **Outbox → 메시지 큐 → EventConsumer** 경로로 전달된다.

```typescript
// application/event/order-cancelled-handler.ts
export class OrderCancelledHandler {
  public async handle(event: { orderId: string; reason: string }): Promise<void> {
    // 후속 처리 (로깅, 알림, Integration Event 발행 등)
  }
}
```

→ 이벤트 발행·수신 상세는 [domain-events.md](domain-events.md) 참조

---

### 읽기 모델 (Query 인터페이스)

QueryHandler는 Aggregate가 아닌 **읽기 전용 모델**을 통해 조회한다.

```typescript
// application/query/order-query.ts — Query 인터페이스 (abstract class)
export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(query: GetOrderQuery): Promise<GetOrderResult>
}

// infrastructure/order-query-impl.ts — 구현체 (DB 직접 접근)
export class OrderQueryImpl extends OrderQuery {
  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    // Aggregate 복원 없이 읽기에 최적화된 쿼리
  }
}
```

DI 바인딩:

```
OrderQuery (abstract)  ←  OrderQueryImpl (구현체)
```

---

### 기존 아키텍처와의 비교

| | 기본 아키텍처 | Handler 기반 CQRS |
|---|---|---|
| 쓰기 진입점 | CommandService 메서드 | CommandHandler.execute() |
| 읽기 진입점 | QueryService 메서드 | QueryHandler.execute() |
| 라우팅 | Service 직접 호출 | CommandBus / QueryBus |
| 유스케이스 단위 | Service 메서드 | Handler 클래스 |
| 읽기/쓰기 분리 | Service 클래스 분리 | Handler + 별도 읽기 모델 |
| 적합한 규모 | 단순~중간 | 중간~복잡 |

두 방식 모두 Domain 레이어 독립성, Aggregate 캡슐화, Repository 패턴은 동일하게 유지한다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 기본 아키텍처 (Service 분리)
- [domain-events.md](domain-events.md) — EventHandler와 Outbox 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 패턴
