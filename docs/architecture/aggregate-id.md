# Aggregate ID 생성

### 원칙

- **ID 생성 위치**: Domain 레이어 (Aggregate 생성자)
- **생성 주체**: 서버 (클라이언트 제공 ID 사용 금지)
- **타입**: `string`
- **형식**: UUID v4 하이픈 제거 32자리 hex 문자열

```
'550e8400e29b41d4a716446655440000'   // 올바른 방식 — 32자리, 하이픈 없음
'550e8400-e29b-41d4-a716-446655440000'  // 잘못된 방식 — 하이픈 포함
1, 2, 3                                  // 잘못된 방식 — auto-increment 숫자
```

**auto-increment 숫자 ID를 사용하지 않는 이유:**
- DB 레코드 수·생성 순서를 외부에 노출한다 (보안)
- 여러 서비스·샤드 간 ID 충돌 가능
- ID가 DB 생성 시점까지 결정되지 않아 Domain 레이어에서 미리 생성 불가

---

### ID 생성 유틸

```typescript
// common/generate-id.ts
import { randomUUID } from 'crypto'

export function generateId(): string {
  return randomUUID().replace(/-/g, '')
}
```

---

### Aggregate에서 사용

```typescript
// domain/order.ts
export class Order {
  public readonly orderId: string

  constructor(params: {
    orderId?: string   // 신규 생성 시 생략, DB 복원 시 전달
    userId: string
    items: OrderItem[]
    status: 'pending' | 'paid' | 'cancelled'
  }) {
    this.orderId = params.orderId ?? generateId()
    // ...
  }
}
```

- **신규 생성**: `orderId`를 생략하면 생성자에서 자동 할당
- **DB 복원**: Repository 구현체가 기존 `orderId`를 그대로 전달

---

### Repository 구현체에서 ID 처리

Repository는 Aggregate의 ID를 그대로 사용한다. DB에서 ID를 새로 발급하지 않는다.

```typescript
// infrastructure/order-repository-impl.ts (개념)
public async saveOrder(order: Order): Promise<void> {
  await persist({
    orderId: order.orderId,   // Aggregate가 이미 가진 ID 사용
    userId: order.userId,
    status: order.status,
    // ...
  })
}
```

---

### 하위 Entity ID

Aggregate 내부의 하위 Entity(OrderItem 등)도 동일하게 UUID v4 기반 string ID를 사용한다. 단, 숫자 auto-increment가 더 적합한 경우(단순 순서 인덱스 등)는 도메인 특성에 따라 결정한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate 생성자 패턴
- [repository-pattern.md](repository-pattern.md) — Repository에서 Aggregate 저장
