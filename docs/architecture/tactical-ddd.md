# 전술적 설계 — Aggregate, Entity, Value Object, Domain Event

전략적 설계(BC 경계, Context Map)가 확정된 후 각 BC 내부를 설계한다.

---

### Aggregate Root

Aggregate Root는 **비즈니스 규칙과 불변식을 캡슐화**하는 객체다. 외부에서 Aggregate 내부 상태를 직접 변경할 수 없다. 상태 변경은 반드시 Aggregate Root의 도메인 메서드를 통해서만 이루어진다.

**핵심 원칙:**
- Aggregate Root 단위로 트랜잭션 경계를 설정한다
- 다른 Aggregate는 ID 참조만 허용한다 (객체 참조 금지)
- 비즈니스 불변식 위반 시 도메인 메서드 내에서 즉시 Error를 throw한다

```typescript
export class Order {
  public readonly orderId: string
  public readonly userId: string
  public readonly items: OrderItem[]
  private _status: 'pending' | 'paid' | 'cancelled'
  private readonly _events: OrderDomainEvent[] = []

  constructor(params: { orderId: string; userId: string; items: OrderItem[]; status: 'pending' | 'paid' | 'cancelled' }) {
    if (params.items.length === 0) throw new Error('주문 항목은 최소 1개 이상이어야 합니다.')
    this.orderId = params.orderId
    this.userId = params.userId
    this.items = params.items
    this._status = params.status
  }

  get status() { return this._status }
  get domainEvents() { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error('이미 취소된 주문입니다.')
    if (this._status === 'paid') throw new Error('결제 완료된 주문은 취소할 수 없습니다.')
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

> Application Service는 비즈니스 로직을 직접 수행하지 않는다. Aggregate 메서드에 위임한다.

---

### Entity

Entity는 **고유 식별자**로 동등성을 판단하는 객체다. 같은 식별자를 가진 두 객체는 속성이 달라도 동일한 객체다. 생명주기(생성→수정→삭제)를 가진다.

```typescript
export class OrderItem {
  public readonly itemId: number
  public readonly name: string
  public readonly price: number
  public readonly quantity: number

  constructor(params: { itemId: number; name: string; price: number; quantity: number }) {
    if (params.price <= 0) throw new Error('상품 가격은 0보다 커야 합니다.')
    if (params.quantity <= 0) throw new Error('수량은 0보다 커야 합니다.')
    Object.assign(this, params)
  }

  equals(other: OrderItem): boolean {
    return this.itemId === other.itemId
  }
}
```

Aggregate Root 내부의 하위 Entity는 Aggregate Root를 통해서만 접근하고 수정한다.

---

### Value Object

Value Object는 **값의 조합**으로 동등성을 판단하는 불변 객체다. 식별자가 없다. 두 Value Object의 모든 속성이 같으면 동일한 객체다.

```typescript
export class Money {
  public readonly amount: number
  public readonly currency: 'KRW' | 'USD'

  constructor(amount: number, currency: 'KRW' | 'USD') {
    if (amount < 0) throw new Error('금액은 0 이상이어야 합니다.')
    this.amount = amount
    this.currency = currency
  }

  equals(other: Money): boolean {
    return this.amount === other.amount && this.currency === other.currency
  }

  add(other: Money): Money {
    if (this.currency !== other.currency) throw new Error('통화가 다릅니다.')
    return new Money(this.amount + other.amount, this.currency)
  }
}
```

**Value Object 사용 기준:**
- 속성만으로 의미를 표현할 수 있고 식별자가 불필요한 경우
- 불변 보장이 필요한 경우 (금액, 주소, 좌표 등)

---

### Domain Event

Domain Event는 **Aggregate 내에서 발생한 중요한 상태 변화**를 나타내는 데이터 클래스다.
과거형 이름을 사용한다(`OrderCancelled`, `UserRegistered`).

```typescript
export class OrderCancelled {
  public readonly orderId: string
  public readonly reason: string
  public readonly cancelledAt: Date

  constructor(params: { orderId: string; reason: string; cancelledAt: Date }) {
    Object.assign(this, params)
  }
}
```

**Domain Event vs Integration Event:**

| | Domain Event | Integration Event |
|---|---|---|
| 범위 | 같은 BC 내부 | BC 간 공개 계약 |
| 생성 주체 | Aggregate 도메인 메서드 | Application EventHandler (Domain Event 수신 후 변환) |
| 스키마 안정성 | 내부에서 자유롭게 변경 가능 | 버전 명시 필수(`order.cancelled.v1`), 하위 호환 유지 |
| 결합 | BC 내부에만 영향 | 외부 BC consumer가 의존 |

→ 상세 발행·수신 패턴은 [domain-events.md](domain-events.md) 참조

---

### Aggregate 경계 결정 기준

어떤 객체를 같은 Aggregate에 묶을지는 아래 기준으로 판단한다.

**같은 Aggregate에 묶는 경우:**
- 함께 생성되고 함께 삭제되는 객체 (생명주기 공유)
- 불변식을 유지하기 위해 항상 함께 변경해야 하는 객체
- 예: `Order`와 `OrderItem` — 항목이 없으면 주문이 성립하지 않는다

**다른 Aggregate로 분리하는 경우:**
- 독립적으로 조회·수정되는 객체
- 한쪽 변경이 다른 쪽 불변식에 영향을 주지 않는 객체
- 참조 빈도는 낮고 크기가 큰 객체
- 예: `Order`와 `User` — 주문 취소가 사용자 정보에 영향을 주지 않는다

**Aggregate가 너무 커지는 신호:**
- 단일 save 메서드가 수십 개의 row를 변경한다
- 다른 Aggregate를 객체로 직접 포함하고 있다
- 트랜잭션 충돌(낙관적 잠금 실패)이 빈번하다

> 경계가 명확하지 않을 때는 **작게 시작한다**. 나중에 합치는 것이 쪼개는 것보다 쉽다.

---

### 설계 원칙 요약

| 원칙 | 내용 |
|---|---|
| 비즈니스 규칙은 Aggregate에 | Application Service는 조율만, 도메인 메서드에 위임 |
| 트랜잭션 경계 = Aggregate 경계 | 한 트랜잭션에서 하나의 Aggregate만 변경 |
| 다른 Aggregate는 ID 참조 | 객체 참조 시 결합 발생 — ID만 보관 |
| Domain/Application은 프레임워크 무의존 | 순수 비즈니스 로직. 프레임워크 데코레이터 사용 금지 |
| 에러 메시지는 타입화 | free-form 문자열 금지 — enum으로 정의 ([error-handling.md](error-handling.md) 참조) |

---

### 관련 문서

- [strategic-ddd.md](strategic-ddd.md) — BC 경계 식별, Context Map
- [layer-architecture.md](layer-architecture.md) — 레이어 역할, 의존 방향
- [repository-pattern.md](repository-pattern.md) — Aggregate 단위 Repository
- [domain-events.md](domain-events.md) — Domain Event 발행·수신
