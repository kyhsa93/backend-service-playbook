# 도메인 이벤트 발행 패턴

### 개념 구분 — Domain Event vs Integration Event

**Domain Event**: 같은 Bounded Context 내부 사건. Aggregate 내부 상태 변화의 결과. 구조가 자유롭게 변하며 외부 BC와 결합되지 않는다.
- 생성: Aggregate 도메인 메서드 내부에서 `_events.push(new OrderCancelled(...))`
- 저장: Repository에서 Outbox에 적재
- 수신: 같은 BC의 `application/event/<domain-event>-handler.ts`

**Integration Event**: 외부 BC · 외부 시스템과의 **공개 계약**. 이름·스키마가 안정적이어야 하며 버전을 명시한다(`order.cancelled.v1`). 소비 측이 의존할 수 있는 유일한 접점.
- 생성: **Application EventHandler**가 Domain Event를 수신한 뒤 필요 시 변환하여 Outbox에 적재 (Aggregate가 직접 만들지 않는다)
- 수신: 외부 BC가 발행한 Integration Event는 `interface/integration-event/` 에서 수신

둘을 구분하지 않으면 BC 간 결합이 커지고 내부 이벤트 리팩토링이 외부 consumer를 깨뜨린다.

---

### 전체 흐름

```
[1. 도메인 로직 실행]
  Command Service → Aggregate 도메인 메서드 호출 → Aggregate 내부에 Domain Event 객체 수집

[2. 저장 — 하나의 트랜잭션]
  Repository.save(aggregate) 내부에서:
    - Aggregate 상태 저장
    - aggregate.domainEvents를 outbox 테이블에 저장
    - aggregate.clearEvents()
  트랜잭션 커밋 → Aggregate와 이벤트가 함께 확정되거나 함께 롤백

[3. Outbox → 메시지 큐 전송]
  OutboxRelay: outbox 테이블을 짧은 주기로 폴링
    → 미전송 이벤트를 메시지 큐(SQS 등)로 전송
    → 전송 완료된 이벤트를 processed 처리

[4. 메시지 큐 → EventHandler 수신 (같은 BC의 Domain Event 처리)]
  EventConsumer: 큐에서 메시지를 수신
    → eventType에 따라 application/event/ 내부 EventHandler 호출
    → 후속 처리 실행 (같은 BC 내 상태 조정, 로깅 등)

[5. (선택) Integration Event 발행 — Application EventHandler가 변환]
  EventHandler가 Domain Event를 외부 BC로 알려야 할 때:
    → IntegrationEventV1 객체를 구성
    → OutboxWriter로 외부 큐용 outbox에 적재
    → 이후 3단계와 동일하게 Relay → 메시지 큐 → 외부 BC

[6. 외부 BC의 Integration Event 수신]
  다른 BC가 발행한 Integration Event가 자기 BC에 들어올 때:
    → interface/integration-event/<domain>-integration-event-controller.ts
    → 핸들러가 수신 → Command Service를 호출하여 자기 도메인의 유스케이스 실행
```

---

### 1단계: Aggregate에서 이벤트 수집

```typescript
export class Order {
  private readonly _events: OrderDomainEvent[] = []

  get domainEvents() { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error('이미 취소된 주문입니다.')
    this._status = 'cancelled'
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

---

### 2단계: Repository에서 Aggregate + Outbox를 트랜잭션으로 저장

Repository 구현체의 save 메서드 안에서 Aggregate 저장과 outbox 저장을 하나의 트랜잭션으로 묶는다. Command Service는 outbox를 직접 다루지 않는다.

```typescript
// infrastructure/order-repository-impl.ts (개념)
public async saveOrder(order: Order): Promise<void> {
  await transaction(async () => {
    await persistAggregate(order)
    if (order.domainEvents.length > 0) {
      await outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  })
}
```

---

### Outbox 테이블 스키마

```
outbox
  eventId    : string (PK, 고유 ID)
  eventType  : string (예: 'OrderCancelled', 'order.cancelled.v1')
  payload    : string (JSON 직렬화)
  processed  : boolean (기본값 false)
  createdAt  : datetime
```

at-least-once 전달을 전제로 한다. EventHandler는 멱등하게 구현해야 한다.

---

### Integration Event 스키마 규칙

```typescript
// application/integration-event/order-cancelled-integration-event.ts
export class OrderCancelledIntegrationEventV1 {
  public readonly eventName = 'order.cancelled.v1'   // 버전 명시
  public readonly orderId: string
  public readonly reason: string
  public readonly cancelledAt: Date
}
```

- 이름 형식: `<domain>.<event>.<version>` (예: `order.cancelled.v1`)
- 하위 호환: 새 필드는 optional로 추가. 기존 필드 제거/수정 시 버전을 올린다(`v2`).
- Integration Event는 Domain Event를 그대로 외부에 노출하지 않는다. Application EventHandler가 변환 지점이다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의
- [cross-domain-communication.md](cross-domain-communication.md) — Integration Event vs Adapter 선택 기준
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
