# Domain Service / Technical Service 패턴

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

### Domain Service vs Application Service vs Technical Service

혼동하기 쉬운 세 개념의 차이 (Technical Service는 아래 섹션 참고):

| | Domain Service | Application Service | Technical Service |
|---|---|---|---|
| 레이어 | Domain | Application (인터페이스) | Application (인터페이스) / Infrastructure (구현) |
| 역할 | 도메인 규칙 계산/판단 | 유스케이스 조율 (Repository 호출, 트랜잭션) | 기술적 관심사 추상화 (암복호화, 스토리지, 외부 API 등) |
| 상태 | 없음 | 없음 | 없음 (캐시 등 내부 구현 상세는 예외) |
| 의존성 | 다른 도메인 객체만 | Repository, Domain Service, Adapter, Technical Service | 외부 라이브러리/SDK (구현체만) |
| 프레임워크 의존 | 없음 | 없음 (단, DI 컨테이너 토큰으로 등록됨) | 없음 (인터페이스는), 구현체는 SDK에 의존 가능 |
| 에러 | plain Error | plain Error | plain Error |

**Application Service**가 유스케이스를 조율하고, **Domain Service**가 그 안의 도메인 판단을, **Technical Service**가 기술적 구현이 핵심인 부분을 담당한다.

---

## Technical Service — 기술 인프라 관심사 분리

암복호화, 파일 스토리지, Secrets Manager, 외부 API 클라이언트, 메시지 큐 클라이언트 등 **기술적 구현이 핵심인 기능**은 Application 레이어에 인터페이스(추상 클래스/인터페이스)를 정의하고, Infrastructure 레이어에서 구현체를 제공한다.

**이유:**
- Application Service가 특정 라이브러리·SDK에 직접 의존하지 않는다.
- 구현 기술이 바뀌어도(예: AES → KMS, S3 → GCS) 구현체만 교체하면 된다.
- 테스트 시 인터페이스를 mock하여 외부 의존 없이 단위 테스트할 수 있다.

**Adapter와의 차이** ([cross-domain-communication.md](cross-domain-communication.md) 참고):
- **Adapter**: 다른 Bounded Context의 Service를 호출하기 위한 인터페이스 (도메인 간 통신)
- **Technical Service**: 기술 인프라 구현을 추상화하기 위한 인터페이스 (기술 관심사 분리, 도메인과 무관)

```
[Order 도메인]
  application/
    service/
      crypto-service      (인터페이스)             ← Application이 필요로 하는 형태로 정의
    command/
      order-command-service  (CryptoService 주입)
  infrastructure/
    crypto-service-impl   (실제 구현체, 예: AES)
```

**Step 1 — Application 레이어에 인터페이스 정의**

```typescript
// application/service/crypto-service — 인터페이스
abstract class CryptoService {
  abstract encrypt(plainText: string): Promise<string>
  abstract decrypt(cipherText: string): Promise<string>
}
```

인터페이스는 **사용하는 쪽(Application Service)이 필요로 하는 형태**로 정의한다. 구현 기술의 세부사항(알고리즘, 키 관리 등)은 인터페이스에 노출하지 않는다.

**Step 2 — Infrastructure 레이어에 구현체 작성**

```typescript
// infrastructure/crypto-service-impl
class CryptoServiceImpl implements CryptoService {
  async encrypt(plainText: string): Promise<string> { /* AES 등 실제 구현 */ }
  async decrypt(cipherText: string): Promise<string> { /* ... */ }
}
```

**Step 3 — Application Service에서 사용**

```typescript
class OrderCommandService {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly cryptoService: CryptoService
  ) {}

  async createOrder(command: CreateOrderCommand): Promise<void> {
    const encryptedAddress = await this.cryptoService.encrypt(command.address)
    // ...
  }
}
```

**Step 4 — DI 등록**: 프레임워크의 DI 컨테이너에 인터페이스 → 구현체를 바인딩한다 (`{ provide: CryptoService, useClass: CryptoServiceImpl }` 형태 또는 언어별 동등한 방식).

> **적용 기준**: 단순 유틸 함수(날짜 포맷, 문자열 변환 등)는 Technical Service로 분리하지 않는다. 외부 시스템 연동이 있거나, 구현 기술이 교체될 가능성이 있는 기술적 관심사에 적용한다.
> 예: 암복호화, 파일 스토리지 ([file-storage.md](file-storage.md)), Secrets Manager ([secret-manager.md](secret-manager.md)), 메시지 큐 클라이언트, 외부 API 클라이언트, 이메일/SMS 발송 등.

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
- [cross-domain-communication.md](cross-domain-communication.md) — Adapter 패턴 (Technical Service와의 차이)
- [secret-manager.md](secret-manager.md), [file-storage.md](file-storage.md) — Technical Service 적용 예시
