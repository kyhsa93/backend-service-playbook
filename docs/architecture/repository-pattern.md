# Repository 패턴

### Aggregate Root 단위 Repository

- **1 Aggregate Root = 1 Repository 인터페이스 + 1 Repository 구현체**
- 인터페이스(abstract class)는 `domain/` 레이어에, 구현체는 `infrastructure/` 레이어에 배치한다.
- Aggregate 내부의 하위 Entity는 Aggregate Root의 Repository를 통해 함께 저장/조회한다.

```
src/
  order/
    domain/
      order-repository.ts          ← abstract class (인터페이스)
    infrastructure/
      order-repository-impl.ts     ← extends OrderRepository (구현체)
```

### DI 바인딩 — abstract class를 토큰으로 사용

Application Service는 abstract class 타입으로 Repository를 주입받는다. 구현체는 Infrastructure 레이어에서 DI 컨테이너를 통해 바인딩한다.

```typescript
// Application Service — abstract class 타입으로 주입받음
constructor(private readonly orderRepository: OrderRepository) {}

// Infrastructure — 구현체 바인딩 (프레임워크별 방식)
// OrderRepository → OrderRepositoryImpl
```

→ 프레임워크별 DI 연결 방법은 `docs/implementations/` 참조

### Repository 메서드 네이밍 규칙

| 목적 | 메서드명 패턴 | 예시 |
|------|--------------|------|
| 목록 조회 | `find<Noun>s` | `findOrders`, `findUsers` |
| 저장/업서트 | `save<Noun>` | `saveOrder`, `saveUser` |
| 삭제 | `delete<Noun>` | `deleteOrder`, `deleteUser` |

- **조회는 항상 `find<Noun>s` 하나만** — 단건/목록 구분 없이 목록 조회 메서드를 사용
- 단건 조회 시 Service에서 `take: 1`로 호출 후 `.then(r => r.<noun>s.pop())` 패턴 사용
- **Repository에 수정(update) 메서드 금지** — 조회 후 Aggregate의 도메인 메서드로 수정, `save<Noun>`으로 저장

### 공통 컬럼 — createdAt, updatedAt, deletedAt

모든 Entity는 생성/수정/삭제 시각을 기록하는 공통 컬럼을 포함한다.

```typescript
// 공통 컬럼 (프레임워크 무관 개념)
createdAt : datetime  — 생성 시각 (자동 설정)
updatedAt : datetime  — 최종 수정 시각 (자동 갱신)
deletedAt : datetime | null  — 삭제 시각 (null이면 미삭제)
```

이 세 컬럼은 공통 BaseEntity 추상 클래스를 만들어 상속하게 한다. 프레임워크별 구현은 `docs/implementations/` 참조.

---

### Soft Delete

데이터 삭제 시 **행을 실제로 제거(hard delete)하지 않는다**. `deletedAt`에 타임스탬프를 기록하는 soft delete를 사용한다.

**이유:**
- 이력 추적 및 감사(audit) 가능
- 실수로 인한 데이터 삭제 복구 가능
- 참조 무결성 오류 없이 안전하게 삭제 처리

```typescript
// Repository 구현체 — soft delete
public async deleteOrder(orderId: string): Promise<void> {
  await db.softDelete(OrderEntity, { orderId })  // deletedAt = now()
}

// 조회 시 삭제된 데이터 자동 제외 (deletedAt IS NULL)
public async findOrders(query: FindOrdersQuery): Promise<{ orders: Order[]; count: number }> {
  // 대부분의 ORM이 deletedAt IS NULL 조건을 자동 적용
}

// 삭제된 데이터를 포함하여 조회해야 하는 경우
public async findOrdersIncludingDeleted(orderId: string): Promise<Order | undefined> {
  // withDeleted 옵션 등 ORM별 방식 사용
}
```

하위 엔티티도 **함께 soft delete**해야 한다. Repository 구현체 내부에서 처리하며, Service는 `delete<Noun>` 한 번만 호출한다:

```typescript
public async deleteOrder(orderId: string): Promise<void> {
  await db.softDelete(OrderItemEntity, { orderId })  // 하위 엔티티 먼저
  await db.softDelete(OrderEntity, { orderId })       // Aggregate Root
}
```

---

### 동적 필터 패턴

조회 조건이 optional인 경우, 값이 있을 때만 조건을 추가하는 방식으로 동적 where를 구성한다.

```typescript
// Repository 구현체 — 조건부 where 체이닝
public async findOrders(query: {
  orderId?: string
  userId?: string
  status?: string[]
  take: number
  page: number
}): Promise<{ orders: Order[]; count: number }> {
  const conditions: Record<string, unknown> = {}

  if (query.orderId) conditions.orderId = query.orderId
  if (query.userId)  conditions.userId  = query.userId
  if (query.status?.length) conditions.status = query.status  // IN 조건

  // 결과는 항상 { orders: Order[]; count: number } 반환
}
```

**원칙:**
- 각 조건은 값이 있을 때만 적용 (`if (query.field)` 가드)
- 배열 조건은 빈 배열(`[]`)도 적용 대상에서 제외 (`if (arr?.length)`)
- 조건이 없으면 전체 조회 — 항상 `take`/`page`로 페이지네이션 적용

---

### 도메인 경계 — Mapping Table 양방향 접근

두 도메인 사이의 경계는 **mapping table**로 정의한다.
mapping table은 연결된 **양쪽 도메인 Repository 구현체 모두**에서 조회/저장/삭제할 수 있어야 한다.
각 Repository 구현체는 **자신의 도메인 식별자**로 mapping table에 접근한다.

```
user ──── userGroupMap ──── group ──── groupRoleMap ──── role
   user 측 식별자: userId          group 측 식별자: groupId
   group 측 식별자: groupId         role 측 식별자: roleId
```

### Repository의 Cascade 저장/삭제

`save<Noun>` / `delete<Noun>` 호출 시 Repository 구현체 내부에서 **하위 엔티티와 연결된 mapping table을 함께 처리**한다.
Service는 cascade 순서를 직접 관리하지 않고, 도메인 단위의 단일 메서드만 호출한다.

```typescript
// infrastructure/group-repository-impl.ts 내부
public async deleteGroup(groupId: string): Promise<void> {
  // FK 참조 순서: mapping tables 먼저 → main entity 순으로 삭제
  await deleteGroupRoleMap(groupId)
  await deleteUserGroupMap(groupId)
  await deleteGroup(groupId)
}
```

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate Root 설계 상세
- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향
- [domain-events.md](domain-events.md) — Repository에서 Domain Event → Outbox 저장
- [persistence.md](persistence.md) — 트랜잭션 전파, Soft Delete, 마이그레이션
