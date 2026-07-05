# 레이어 아키텍처

### 의존 방향

```
Interface (Controller)  →  Application (Service)  →  Domain (Aggregate, Repository 인터페이스)
                                                          ↑
                                                   Infrastructure (Repository 구현체)
```

- 상위 레이어는 하위 레이어에 의존할 수 있지만, 하위 레이어는 상위 레이어에 의존하지 않는다.
- Domain 레이어는 어떤 레이어에도 의존하지 않는다 (프레임워크, ORM 포함).
- Infrastructure 레이어는 Domain 레이어의 인터페이스를 구현한다 (의존성 역전).

> 코드 예시는 TypeScript를 사용하지만 프레임워크 데코레이터(`@Injectable` 등)를 포함하지 않는다.
> 프레임워크별 구현 상세는 `docs/implementations/` 참조.

---

### Domain 레이어

비즈니스 규칙의 핵심. **어떤 프레임워크에도 의존하지 않는 순수한 코드**로 작성한다.

1. **Aggregate Root** — 비즈니스 규칙과 불변식 캡슐화
2. **Entity** — 고유 식별자를 가지며 생명주기가 있는 객체
3. **Value Object** — 불변 객체. 속성의 조합으로 동등성 판단
4. **Domain Event** — 도메인에서 발생한 중요한 사건을 나타내는 데이터 클래스
5. **Repository 인터페이스** — Aggregate Root 단위로 정의한 abstract class. 구현은 Infrastructure 레이어에 배치

```typescript
// domain/order-repository.ts — Repository 인터페이스 (abstract class)
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

→ Aggregate, Entity, Value Object, Domain Event 상세는 [tactical-ddd.md](tactical-ddd.md) 참조

---

### Application 레이어 — 조율자

Application Service는 **Command Service**(쓰기)와 **Query Service**(읽기)로 분리한다.

#### Command Service

데이터를 변경하는 유스케이스를 담당한다. 비즈니스 로직은 직접 수행하지 않고 Aggregate에 위임한다.

1. Repository에서 Aggregate 조회
2. Aggregate의 도메인 메서드 호출
3. Repository로 Aggregate 저장

```typescript
// application/command/order-command-service.ts
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async cancelOrder(command: CancelOrderCommand): Promise<void> {
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

#### Query Service

데이터를 조회하는 유스케이스를 담당한다. Repository를 직접 사용하지 않고, application 레이어의 **Query 인터페이스**(abstract class)를 주입받는다. Query 구현체는 Infrastructure 레이어에 배치한다.

```typescript
// application/query/order-query.ts — Query 인터페이스 (abstract class)
export abstract class OrderQuery {
  abstract getOrders(query: GetOrdersQuery): Promise<GetOrdersResult>
  abstract getOrder(query: GetOrderQuery): Promise<GetOrderResult>
}
```

```typescript
// application/query/order-query-service.ts
export class OrderQueryService {
  constructor(private readonly orderQuery: OrderQuery) {}

  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    return this.orderQuery.getOrders(query)
  }
}
```

#### Command/Query 분리 원칙

- **Repository**는 Command Service에서만 사용한다. Aggregate 단위의 조회/저장을 담당한다.
- **Query 인터페이스**는 Query Service에서만 사용한다. 읽기에 최적화된 조회를 담당하며, Aggregate 복원이 불필요하다.
- Interface 레이어에서 쓰기 요청은 Command Service를, 읽기 요청은 Query Service를 호출한다.

---

### Infrastructure 레이어

1. **Repository 구현체** — Domain 레이어의 abstract class를 구현한다. ORM 클라이언트를 직접 사용하는 유일한 레이어.
2. **Query 구현체** — Application 레이어의 Query abstract class를 구현한다. 읽기에 최적화된 쿼리를 직접 작성한다.
3. **이벤트 발행** — 메시지 큐 연동, 이벤트 직렬화.
4. **외부 시스템 어댑터** — Anticorruption Layer. 외부 API 응답을 도메인 모델로 변환.

```typescript
// infrastructure/order-query-impl.ts — Query 구현체
export class OrderQueryImpl extends OrderQuery {
  public async getOrders(query: GetOrdersQuery): Promise<GetOrdersResult> {
    // DB에서 직접 조회 — Aggregate 복원 불필요, 읽기에 최적화된 쿼리 사용
  }
}
```

Infrastructure 레이어에서 DI를 통해 Repository와 Query 구현체를 Domain/Application 인터페이스에 바인딩한다:

```
OrderRepository (abstract)  ←  OrderRepositoryImpl (구현체)
OrderQuery (abstract)        ←  OrderQueryImpl (구현체)
```

→ 프레임워크별 DI 연결 방법은 `docs/implementations/` 참조

#### 트랜잭션 전파 — AsyncLocalStorage 패턴

여러 Repository에 걸친 쓰기 작업을 하나의 트랜잭션으로 묶을 때, 트랜잭션 클라이언트를 **함수 인자 없이 암묵적으로 전파**하는 패턴이다.

```typescript
// 개념 — TransactionManager
const transactionStorage = new AsyncLocalStorage<TxClient>()

class TransactionManager {
  async run<T>(fn: () => Promise<T>): Promise<T> {
    return db.transaction((txClient) =>
      transactionStorage.run(txClient, fn)  // 콜백 내부에서 txClient를 ALS로 전파
    )
  }

  getClient(): TxClient {
    return transactionStorage.getStore() ?? db.defaultClient  // ALS에 있으면 tx 클라이언트, 없으면 기본 클라이언트
  }
}
```

```typescript
// Application Service
await transactionManager.run(async () => {
  await paymentRepository.deletePaymentMethods(order.orderId)  // tx 안에서 실행
  await orderRepository.saveOrder(order)                        // 같은 tx
})

// Repository 구현체 (인자로 tx를 받지 않아도 됨)
public async saveOrder(order: Order): Promise<void> {
  const client = transactionManager.getClient()  // ALS에서 자동으로 tx 클라이언트 획득
  await client.save(...)
}
```

**장점:** Application Service가 트랜잭션 클라이언트를 Repository에 직접 전달하지 않아도 된다. Repository 인터페이스가 트랜잭션 개념에 오염되지 않는다. 트랜잭션 바깥에서 호출하면 기본 클라이언트를 사용하므로 자연스럽게 동작한다.

---

### Interface 레이어

외부 요청(HTTP, 메시지 큐 등)의 진입점이다.

1. 요청 수신
2. Command Service 또는 Query Service 호출
3. 에러 캐치 → HTTP/프로토콜 예외로 변환

#### Interface DTO = Application 객체의 thin wrapper

Interface DTO는 Application 레이어의 Query/Result/Command를 `extends`로 감싼다. 별도 로직이나 변환 없이 얇게 감싸는 것이 원칙이다.

```typescript
// interface/dto/get-order-request-param.ts
import { GetOrderQuery } from '@/order/application/query/get-order-query'
export class GetOrderRequestParam extends GetOrderQuery {}

// interface/dto/delete-order-request-param.ts
import { DeleteOrderCommand } from '@/order/application/command/delete-order-command'
export class DeleteOrderRequestParam extends DeleteOrderCommand {}
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Bus 기반 패턴 (선택 적용)
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
