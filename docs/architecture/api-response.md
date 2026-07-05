# API 응답 구조

### 페이지네이션

오프셋 기반 페이지네이션을 기본으로 사용한다.

| 파라미터 | 타입 | 설명 | 기본값 |
|---------|------|------|--------|
| `page` | number | 페이지 번호 (0부터 시작) | 0 |
| `take` | number | 페이지 크기 | 20 |
| `sort` | string | 정렬 기준 (`createdAt:desc`) | 선택 |

```
GET /orders?page=0&take=20&status=pending&status=paid&sort=createdAt:desc
```

**page는 0부터 시작하는 이유:** `skip = page * take` 계산이 자연스럽다. `page=0`이면 `skip=0`으로 첫 페이지를 가져온다.

---

### 목록 조회 응답 형식

```json
{
  "orders": [
    { "orderId": "abc123", "status": "pending", "totalAmount": 30000 }
  ],
  "count": 42
}
```

**원칙:**
- 키 이름은 **도메인 객체명 복수형** (`orders`, `users`, `payments`)
- `result`, `data`, `items` 같은 범용 키 사용 금지
- `count`는 필터 적용 후 전체 건수 (현재 페이지 크기가 아님)

---

### 단건 조회 응답 형식

```json
{
  "orderId": "abc123",
  "status": "pending",
  "totalAmount": 30000,
  "items": [
    { "itemId": "item-1", "quantity": 2, "price": 15000 }
  ]
}
```

범용 래퍼(`{ success: true, data: { ... } }`)로 감싸지 않는다. 도메인 객체를 직접 반환한다.

**범용 래퍼를 사용하지 않는 이유:** 에러와 정상 응답의 구분은 HTTP 상태 코드가 담당한다. 래퍼는 중복이며 클라이언트 코드에 불필요한 unwrapping 계층을 추가한다.

---

### Repository 조회 메서드 반환 형식

목록 조회 메서드는 항상 **도메인 객체 배열 + count**를 반환한다.

```typescript
// domain/order-repository.ts
export abstract class OrderRepository {
  abstract findOrders(query: {
    orderId?: string
    userId?: string
    status?: string[]
    take: number
    page: number
  }): Promise<{ orders: Order[]; count: number }>
}
```

---

### 단건 조회 — 별도 메서드 없음

`findOne` 메서드를 따로 만들지 않는다. `take: 1`을 전달하고 `.then()` 체이닝으로 꺼낸다.

```typescript
const order = await this.orderRepository
  .findOrders({ orderId, take: 1, page: 0 })
  .then((r) => r.orders.pop())

if (!order) throw new Error(OrderErrorMessage['주문을 찾을 수 없습니다.'])
```

**이유:** `findOne`과 `findMany`를 별도로 두면 동적 필터 조건 등 중복 구현이 발생한다. 조회 경로를 하나로 통일하면 Repository 구현체가 단순해진다.

---

### 동적 필터 조건 패턴

목록 조회 쿼리에서 조건은 **값이 있을 때만 적용**한다.

```typescript
// infrastructure 구현체 (개념)
public async findOrders(query: FindOrdersQuery) {
  const conditions: Condition[] = []

  if (query.orderId) conditions.push(eq('orderId', query.orderId))
  if (query.userId)  conditions.push(eq('userId', query.userId))
  if (query.status?.length) conditions.push(inArray('status', query.status))

  return queryDb({ conditions, take: query.take, skip: query.page * query.take })
}
```

`undefined` 조건을 포함하면 의도치 않게 전체 조회가 되거나 쿼리 오류가 발생한다. 각 조건을 `if` 가드로 감싼다.

---

### Result 객체 설계

Query Service가 반환하는 Result 객체는 응답 스키마를 정의한다. 도메인 Aggregate를 직접 반환하지 않는다.

```typescript
// application/query/get-orders-result.ts
export class GetOrdersResult {
  public readonly orders: GetOrderResult[]
  public readonly count: number
}

export class GetOrderResult {
  public readonly orderId: string
  public readonly status: string
  public readonly totalAmount: number
  public readonly createdAt: Date
}
```

**도메인 Aggregate를 응답으로 직접 노출하지 않는 이유:**
- Aggregate는 비즈니스 로직과 내부 상태를 포함한다. 직렬화하면 내부 구현이 외부에 노출된다.
- 조회에 필요한 필드만 포함한 Result 객체는 Aggregate보다 가볍고 변경에 유연하다.

---

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계
- [layer-architecture.md](layer-architecture.md) — Query Service, Result 객체
- [conventions.md](../conventions.md) — REST API URL 설계 원칙
