# 테스트 전략

3개 레이어로 테스트를 구성한다. 각 레이어는 검증 범위와 의존성 전략이 다르다.

| 레이어 | 검증 범위 | 의존성 전략 | 실행 속도 |
|--------|----------|------------|----------|
| Domain 단위 테스트 | Aggregate, Value Object, Domain Service | 프레임워크 없음 (순수 비즈니스 로직) | 매우 빠름 |
| Application 단위 테스트 | Command/Query Service, Handler | Repository, Adapter를 mock | 빠름 |
| E2E 테스트 | Interface → Application → Infrastructure 전체 경로 | 실제 DB (in-memory 또는 컨테이너) | 느림 |

---

### Domain 단위 테스트

프레임워크 없이 순수 비즈니스 로직만 검증한다. 외부 의존성이 없으므로 매우 빠르게 실행된다.

**테스트 대상:** Aggregate 불변식, 도메인 메서드, Value Object 동등성, Domain Service 판단 로직

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

  it('주문_항목이_비어있으면_생성_시_에러를_throw한다', () => {
    expect(() => createOrder({ items: [] }))
      .toThrow('주문 항목은 최소 1개 이상이어야 합니다.')
  })

  it('이미_취소된_주문을_cancel_시_에러를_throw한다', () => {
    const order = createOrder({ status: 'cancelled' })
    expect(() => order.cancel('변심')).toThrow('이미 취소된 주문입니다.')
  })

  it('cancel_시_OrderCancelled_이벤트가_수집된다', () => {
    const order = createOrder()
    order.cancel('변심')
    expect(order.domainEvents).toHaveLength(1)
    expect(order.domainEvents[0]).toBeInstanceOf(OrderCancelled)
  })
})
```

**원칙:**
- 테스트 픽스처는 헬퍼 함수(`createOrder`)로 기본값을 두고 `overrides`로 변형
- 에러 메시지는 enum 참조 (문자열 하드코딩 금지)
- 프레임워크 모듈을 import하지 않는다

---

### Application 단위 테스트

Repository, Adapter 등 외부 의존성을 **mock**으로 대체한다. 유스케이스의 조율 로직을 검증한다.

**테스트 대상:** Command Service의 조율 흐름, 에러 전파, 트랜잭션 경계

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

  it('주문이_존재하지_않으면_에러를_throw한다', async () => {
    orderRepository.findOrders.mockResolvedValue({ orders: [], count: 0 })

    await expect(service.cancelOrder({ orderId: 'non-existent', reason: '변심' }))
      .rejects.toThrow(OrderErrorMessage['주문을 찾을 수 없습니다.'])
  })

  it('주문_취소_시_saveOrder가_호출된다', async () => {
    const order = new Order({ orderId: 'order-1', userId: 'user-1',
      items: [{ itemId: 'i1', quantity: 1, price: 1000 }], status: 'pending' })
    orderRepository.findOrders.mockResolvedValue({ orders: [order], count: 1 })

    await service.cancelOrder({ orderId: 'order-1', reason: '변심' })

    expect(orderRepository.saveOrder).toHaveBeenCalledWith(
      expect.objectContaining({ status: 'cancelled' })
    )
  })
})
```

**원칙:**
- Repository mock은 abstract class를 타입으로 사용 (구체 클래스 mock 금지)
- mock은 반드시 abstract class의 메서드 시그니처와 일치
- 비즈니스 로직은 Domain 단위 테스트에서 검증. Application 테스트는 조율 흐름만

---

### E2E 테스트

Interface → Application → Infrastructure 전체 경로를 실제 DB와 함께 검증한다.

**테스트 대상:** HTTP 엔드포인트 통합, 실제 DB 저장/조회, 트랜잭션 롤백

```typescript
// test/order.e2e-spec.ts
describe('OrderController (e2e)', () => {
  let app: Application
  let db: Database

  beforeAll(async () => {
    db = await setupInMemoryDb()
    app = await createApp({ db })
  })

  it('GET /orders/:orderId — 존재하는 주문 반환', async () => {
    const orderId = await createTestOrder(db)

    const response = await request(app).get(`/orders/${orderId}`)
      .set('Authorization', `Bearer ${testToken}`)

    expect(response.status).toBe(200)
    expect(response.body.orderId).toBe(orderId)
  })

  it('GET /orders/:orderId — 없는 주문은 404', async () => {
    const response = await request(app).get('/orders/non-existent')
      .set('Authorization', `Bearer ${testToken}`)

    expect(response.status).toBe(404)
    expect(response.body.code).toBe('ORDER_NOT_FOUND')
  })

  afterAll(() => app.close())
})
```

**원칙:**
- E2E 테스트는 in-memory DB (SQLite 등) 또는 컨테이너(testcontainers) 사용. 운영 DB 사용 금지
- 각 테스트는 독립적으로 실행 가능해야 함 (테스트 간 상태 공유 금지)
- 실제 HTTP 요청으로 검증 — 프레임워크 내부를 우회하지 않는다

---

### 테스트 파일 배치

```
src/
  order/
    domain/
      order.spec.ts                      ← Domain 단위 테스트 (소스 옆에)
    application/
      command/
        order-command-service.spec.ts    ← Application 단위 테스트 (소스 옆에)
test/
  order.e2e-spec.ts                      ← E2E 테스트 (별도 디렉토리)
```

---

### 테스트 네이밍 패턴

```
<행위>_when_<조건>_then_<기대_결과>
예: cancelOrder_when_이미취소됨_then_에러를throw한다
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain 레이어 설계 (단위 테스트 대상)
- [layer-architecture.md](layer-architecture.md) — Application Service (Application 단위 테스트 대상)
- [error-handling.md](error-handling.md) — 에러 응답 형식 (E2E 테스트 검증 포인트)
