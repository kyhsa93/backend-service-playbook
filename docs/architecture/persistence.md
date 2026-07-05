# 영속성 패턴 — 트랜잭션, Entity 공통 컬럼, Soft Delete, 마이그레이션

Repository 구현체([repository-pattern.md](repository-pattern.md))가 실제로 데이터를 다룰 때 따르는 공통 규칙이다. ORM/쿼리 빌더의 구체적인 문법은 언어/프레임워크마다 다르지만, 아래 패턴 자체는 동일하게 적용된다.

---

## 트랜잭션 전파 — Unit of Work

여러 Repository에 걸친 쓰기 작업을 하나의 트랜잭션으로 묶는다. 트랜잭션 객체를 모든 호출에 명시적으로 전달하는 대신, **컨텍스트-로컬 저장소**(언어별로 Node의 `AsyncLocalStorage`, Go의 `context.Context`, Java/Kotlin의 `ThreadLocal`, Python의 `contextvars` 등)를 사용해 암묵적으로 전파한다.

### TransactionManager (Infrastructure 레이어)

```typescript
// database/transaction-manager — 개념
class TransactionManager {
  private readonly storage = createContextLocalStorage<TransactionClient>()

  // 트랜잭션 내에서 콜백을 실행한다
  async run<T>(fn: () => Promise<T>): Promise<T> {
    return this.dataSource.transaction((client) =>
      this.storage.run(client, fn)
    )
  }

  // 트랜잭션 컨텍스트가 있으면 tx client, 없으면 기본 client를 반환한다
  getClient(): TransactionClient {
    return this.storage.getStore() ?? this.dataSource.defaultClient
  }
}
```

### Repository 구현체에서 사용

Repository 구현체는 `transactionManager.getClient()`로 현재 트랜잭션 컨텍스트를 자동으로 전파받는다. 트랜잭션 밖에서 호출되면 기본 클라이언트로 동작하므로 별도 분기가 필요 없다.

```typescript
class OrderRepositoryImpl implements OrderRepository {
  constructor(private readonly transactionManager: TransactionManager) {}

  async saveOrder(order: Order): Promise<void> {
    const client = this.transactionManager.getClient()
    await client.save(OrderTable, { ... })
  }
}
```

### 여러 Repository를 묶을 때

```typescript
async cancelOrder(command: CancelOrderCommand): Promise<void> {
  const order = await this.orderRepository
    .findOrders({ orderId: command.orderId, take: 1, page: 0 })
    .then((r) => r.orders.pop())
  if (!order) throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])

  order.cancel(command.reason)

  await this.transactionManager.run(async () => {
    await this.paymentRepository.deletePaymentMethods(order.orderId)
    await this.orderRepository.saveOrder(order)   // 내부에서 outbox 저장도 함께
  })
}
```

단일 Repository만 호출하는 경우 `run()`으로 감쌀 필요 없이 바로 호출한다 — Repository 구현체 내부에서 여러 테이블을 조작하더라도 `getClient()`가 이미 있는 트랜잭션 컨텍스트를 재사용한다.

---

## Entity 공통 컬럼 — createdAt, updatedAt, deletedAt

모든 테이블은 `createdAt`, `updatedAt`, `deletedAt` 컬럼을 포함한다. 공통 컬럼은 베이스 클래스/믹스인으로 정의해 모든 Entity가 상속·재사용하도록 한다.

```typescript
// database/base-entity — 개념
abstract class BaseEntity {
  createdAt: Date
  updatedAt: Date
  deletedAt: Date | null
}
```

---

## Soft Delete

데이터 삭제 시 실제 삭제(hard delete)가 아닌 `deletedAt`에 타임스탬프를 기록하는 soft delete를 기본으로 사용한다.

```typescript
// 올바른 방식 — soft delete
async deleteOrder(orderId: string): Promise<void> {
  const client = this.transactionManager.getClient()
  await client.softDelete(OrderTable, { orderId })
}

// 잘못된 방식 — hard delete
async deleteOrder(orderId: string): Promise<void> {
  const client = this.transactionManager.getClient()
  await client.delete(OrderTable, { orderId })   // 실제 삭제 — 사용 금지
}
```

- **조회 시 기본적으로 `deletedAt IS NULL` 조건이 적용**되어야 한다 (ORM의 soft-delete 기능을 쓰거나, 쿼리에 조건을 명시적으로 추가).
- 삭제된 데이터 조회가 필요한 경우에만 별도 옵션(`withDeleted` 등)으로 명시적으로 포함한다.
- 하위 엔티티도 함께 soft delete해야 한다면 Repository 구현체 내부에서 명시적으로 순서대로 처리한다 (부모 테이블의 FK 제약 등을 고려해 자식부터 삭제).

---

## 마이그레이션

스키마 변경은 마이그레이션 파일로 관리한다. `synchronize`/`ddl-auto: update` 같은 자동 스키마 동기화는 **개발 환경 전용**이며, 운영 환경에서는 반드시 마이그레이션을 사용한다 (프로덕션에서 자동 동기화를 켜두면 배포 시 의도치 않은 스키마 변경이 발생할 수 있다).

```
migrations/
  20240401120000_create_order.sql
  20240402090000_add_order_status.sql
```

```bash
# 마이그레이션 생성 — 스키마 변경 사항을 감지하거나 수동 작성
<migration-tool> generate create_order

# 마이그레이션 실행
<migration-tool> migrate up

# 마이그레이션 롤백 (마지막 1개)
<migration-tool> migrate down
```

### 원칙

- **스키마 변경 후 반드시 마이그레이션 생성**: 자동 동기화는 로컬 개발에서만 사용한다.
- **마이그레이션 파일은 커밋에 포함**: 자동 생성된 파일도 검토 후 커밋한다.
- **롤백 가능한 마이그레이션 작성**: up/down(또는 동등한 대칭 오퍼레이션)을 모두 구현한다.
- **데이터 마이그레이션은 스키마 변경과 분리**: 같은 마이그레이션 파일에 스키마 변경과 데이터 변환을 함께 넣지 않는다.

---

## 원칙 요약

- **트랜잭션은 컨텍스트-로컬 저장소로 암묵 전파**한다. 모든 호출에 트랜잭션 객체를 명시적으로 넘기지 않는다.
- **모든 테이블에 createdAt/updatedAt/deletedAt을 둔다.**
- **삭제는 기본적으로 soft delete**를 사용한다. hard delete는 예외적인 경우에만 명시적으로 사용한다.
- **스키마 변경은 마이그레이션으로 관리**한다. 자동 동기화는 로컬 전용이다.

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [domain-events.md](domain-events.md) — Outbox 저장도 같은 트랜잭션에서 처리
