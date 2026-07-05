# Domain Service 패턴

### Domain Service가 필요한 경우

**Aggregate Root에 넣을 수 없는** 도메인 로직이 있을 때 사용한다.

| 상황 | 이유 |
|---|---|
| 여러 Aggregate를 읽어서 판단해야 하는 로직 | 하나의 Aggregate는 다른 Aggregate를 직접 참조하지 않는다 |
| 단일 Aggregate에 속하지 않는 도메인 규칙 | 어떤 Aggregate의 책임인지 불명확한 규칙 |
| 외부 서비스 호출이 포함된 도메인 연산 | Aggregate는 외부 IO를 가지지 않는다 |

> Domain Service는 **상태를 가지지 않는다**. 로직만 담는다. 상태가 필요하다면 설계를 재검토한다.

---

### 위치 및 네이밍

- 파일 위치: `<domain>/domain/<domain-service-name>.ts`
- 클래스명: 도메인 행위를 나타내는 이름 (`OrderPricingService`, `StockValidationService`)
- Domain 레이어에 위치하므로 **프레임워크 데코레이터를 사용하지 않는다**
- Application Service에서 생성자 주입으로 사용한다

```typescript
// domain/order-pricing-service.ts — Domain Service
export class OrderPricingService {
  public calculateDiscount(
    order: Order,
    coupon: { discountAmount: number; minimumAmount: number; isExpired: () => boolean }
  ): number {
    if (coupon.isExpired()) throw new Error(OrderErrorMessage['쿠폰이 만료되었습니다.'])
    if (order.getTotalAmount() < coupon.minimumAmount) return 0
    return Math.min(coupon.discountAmount, order.getTotalAmount())
  }
}
```

```typescript
// application/command/order-command-service.ts — Command Service에서 Domain Service 호출
export class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly orderPricingService: OrderPricingService
  ) {}

  public async applyCoupon(command: ApplyCouponCommand): Promise<void> {
    const order = await this.orderRepository
      .findOrders({ orderId: command.orderId, take: 1, page: 0 })
      .then((r) => r.orders.pop())
    if (!order) throw new Error(OrderErrorMessage['주문을 찾을 수 없습니다.'])

    const discount = this.orderPricingService.calculateDiscount(order, command.coupon)
    order.applyDiscount(discount)

    await this.orderRepository.saveOrder(order)
  }
}
```

---

### Domain Service vs Application Service

혼동하기 쉬운 두 개념의 차이:

| | Domain Service | Application Service |
|---|---|---|
| 레이어 | Domain | Application |
| 역할 | 도메인 규칙 계산/판단 | 유스케이스 조율 (Repository 호출, 트랜잭션) |
| 상태 | 없음 | 없음 |
| 의존성 | 다른 도메인 객체만 | Repository, Domain Service, Adapter |
| 프레임워크 의존 | 없음 | 없음 (단, DI 컨테이너 토큰으로 등록됨) |
| 에러 | plain Error | plain Error |

**Application Service**가 유스케이스를 조율하고, **Domain Service**가 그 안의 도메인 판단을 담당한다.

---

### Domain Service를 잘못 쓰는 패턴

**잘못된 예: DB 조회를 Domain Service 안에서**

```typescript
// 잘못된 방식 — Domain Service가 Repository를 직접 사용
export class OrderValidationService {
  constructor(private readonly orderRepository: OrderRepository) {} // ← 금지

  public async validateOrder(orderId: string): Promise<boolean> {
    const { orders } = await this.orderRepository.findOrders(...)  // ← 금지
    ...
  }
}
```

Domain Service는 이미 조회된 도메인 객체를 받아서 판단만 한다. 조회 자체는 Application Service의 책임이다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity 설계
- [layer-architecture.md](layer-architecture.md) — 레이어별 역할 분리
- [error-handling.md](error-handling.md) — 에러 메시지 enum 패턴
