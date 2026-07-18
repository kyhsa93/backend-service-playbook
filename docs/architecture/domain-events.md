# 도메인 이벤트 발행 패턴

### 개념 구분 — Domain Event vs Integration Event

**Domain Event**: 같은 Bounded Context 내부 사건. Aggregate 내부 상태 변화의 결과. 구조가 자유롭게 변하며 외부 BC와 결합되지 않는다.
- 생성: Aggregate 도메인 메서드 내부에서 `_events.push(new OrderCancelled(...))`
- 저장: **Repository.save()가 Aggregate를 영속화하는 트랜잭션 안에서** Outbox에 함께 적재 (별도 저장 호출이 아니다)
- 수신: 같은 BC의 `application/event/<domain-event>-handler.ts` — 이 핸들러는 Aggregate가 이벤트를 만든 시점에 바로 실행되는 것이 아니라, **Outbox에 저장되어 있던 이벤트를 Relay가 나중에 읽어 넘겨줄 때** 실행된다

**Integration Event**: 외부 BC · 외부 시스템과의 **공개 계약**. 이름·스키마가 안정적이어야 하며 버전을 명시한다(`order.cancelled.v1`). 소비 측이 의존할 수 있는 유일한 접점.
- 생성: **Application EventHandler**가 (Outbox에서 전달받은) Domain Event를 처리하는 도중 필요 시 변환하여, 같은 방식으로 Outbox에 적재 (Aggregate가 직접 만들지 않는다)
- 수신: 외부 BC가 발행한 Integration Event도 동일한 원칙을 따른다 — 발행 측 Outbox에 저장된 이벤트가 전달되면, 수신 측은 `interface/integration-event/`에서 수신

둘을 구분하지 않으면 BC 간 결합이 커지고 내부 이벤트 리팩토링이 외부 consumer를 깨뜨린다.

---

### 전체 흐름

핵심은 세 가지다: **(1) Outbox 저장은 Repository.save()가 Aggregate를 영속화하는 그 트랜잭션 안에서 함께 일어난다** — Command Service나 다른 어떤 코드도 outbox에 별도로 쓰지 않는다. **(2) Outbox에 저장된 이벤트를 메시지 큐로 실어 나르는 것은 Command Service가 아니라 독립적으로 주기 실행되는 Poller의 책임**이다 — Command Service는 저장이 끝나면 그 즉시 요청을 끝내고, 이벤트가 언제 처리되는지는 전혀 알지 못하고 관여하지도 않는다. **(3) EventHandler는 메시지 큐에서 이벤트를 직접 수신하는 Consumer를 통해서만 실행된다** — Aggregate가 이벤트를 만드는 시점, Outbox에 쌓인 이벤트가 큐로 나가는 시점, Consumer가 그 이벤트를 처리하는 시점은 서로 다른 세 개의 독립적인 흐름이며 **동기적으로 이어지는 예외는 없다.**

```
[1. 도메인 로직 실행]
  Command Service → Aggregate 도메인 메서드 호출 → Aggregate 내부에 Domain Event 객체 수집

[2. 저장 — 하나의 트랜잭션]
  Repository.save(aggregate) 내부에서:
    - Aggregate 상태 저장
    - aggregate.domainEvents를 outbox 테이블에 저장
    - aggregate.clearEvents()
  트랜잭션 커밋 → Aggregate와 이벤트가 함께 확정되거나 함께 롤백
  Command Service는 여기서 끝난다 — 이후 어떤 단계도 호출하지 않는다.

[3. OutboxPoller — 독립적으로 주기 실행, 저장된 이벤트를 큐로 전송]
  OutboxPoller: 별도 스케줄(예: 1~2초 주기)로 단독 실행된다
    → outbox 테이블에서 processed=false인 행을 읽는다
    → 각 행을 메시지 큐(SQS 등)로 발행한다 (eventType은 메시지 속성, payload는 본문)
    → 발행에 성공한 행은 즉시 processed=true로 표시한다
       (여기서 processed는 "핸들러가 처리를 끝냈다"가 아니라 "큐로 전달을 끝냈다"는 뜻이다 —
        이후의 전달 보장은 outbox가 아니라 메시지 큐 자신의 재전달 메커니즘이 담당한다)

[4. OutboxConsumer — 독립적으로 큐를 수신 대기, EventHandler 호출]
  OutboxConsumer: 메시지 큐를 짧은 주기로 수신 대기한다(long polling)
    → 메시지를 받으면 eventType에 따라 application/event/ 내부 EventHandler를 호출
    → 핸들러가 성공하면 메시지를 삭제(ack)
    → 핸들러가 실패하면 메시지를 삭제하지 않는다 — 큐의 visibility timeout이 지나면
      자동으로 다시 수신되어 재시도된다(at-least-once, DLQ로 격리 가능)

[5. (선택) Integration Event 발행 — Application EventHandler가 변환]
  EventHandler가 Domain Event를 외부 BC로 알려야 할 때:
    → IntegrationEventV1 객체를 구성
    → OutboxWriter로 외부 BC용 outbox 행을 (같은 트랜잭션에서) 적재
    → 이후 3~4단계와 동일한 경로(Poller → 큐 → Consumer)로 외부 BC에 전달된다

[6. 외부 BC의 Integration Event 수신]
  다른 BC가 발행한 Integration Event가 자기 BC에 들어올 때:
    → interface/integration-event/<domain>-integration-event-controller.ts
    → 핸들러가 수신 → Command Service를 호출하여 자기 도메인의 유스케이스 실행
```

**"같은 프로세스 안에서 저장 직후 동기적으로 드레인"하는 방식은 쓰지 않는다.** Command Service가 저장 트랜잭션을 커밋한 뒤 곧바로 Relay/Consumer를 호출해 그 자리에서 이벤트를 처리해버리면, Outbox 패턴이 원래 분리하려던 "쓰기"와 "이벤트 처리"가 다시 한 요청 안에 묶여버린다 — Poller/Consumer가 항상 독립적으로 실행되어야 이 분리가 실제로 보장된다. 예외 없이 모든 Domain Event/Integration Event가 이 경로를 따른다.

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
  processed  : boolean (기본값 false) — OutboxPoller가 메시지 큐로 발행을 마치면 true.
               핸들러가 실제로 처리를 끝냈는지는 이 컬럼이 아니라 메시지 큐가 안다(ack/재전달).
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

---

### Task Queue vs Domain Event

둘 다 비동기 처리이지만 **용도와 의미 단위**가 다르다.

| | Domain Event | Task Queue |
|---|---|---|
| 의미 단위 | 사실(past): "X가 일어났다" | 명령(imperative): "X를 수행하라" |
| 생산 주체 | Aggregate (도메인 메서드 내부) | Scheduler / Application Service |
| 핸들러 수 | 1:N (하나의 이벤트를 여러 핸들러가 구독) | 1:1 (taskType당 하나의 핸들러) |
| 예시 | `OrderCancelled` → 환불·재고 복원·알림 동시 처리 | 만료 주문 정리 배치, 알림 재전송 |

**판단 기준:** "이건 Command 실행의 결과를 관찰하는 것인가?" → Domain Event. "이 작업을 비동기로 실행하고 싶다" → Task Queue.

---

### 이벤트 핸들러 멱등성

메시지 큐는 **at-least-once delivery**를 보장한다. 즉 같은 이벤트가 두 번 이상 전달될 수 있다. EventHandler는 반드시 멱등하게 구현해야 한다.

**3단계 멱등성 전략:**

| 수준 | 상황 | 구현 방식 |
|------|------|----------|
| Level 1 — 본질적 멱등 | 핸들러 로직 자체가 반복 실행되어도 결과가 동일 | 별도 장치 불필요 |
| Level 2 — Ledger | 부작용이 있는 핸들러 (외부 API 호출, 환불 처리 등) | 처리 기록을 DB에 저장, 중복 수신 시 skip |
| Level 3 — 강한 원자성 | "성공한 경우에만 기록"이 필요한 경우 | 핸들러 로직과 ledger 저장을 같은 트랜잭션으로 묶음 |

**Level 1 예시 — 본질적 멱등:**

```typescript
// 상태 기반 처리: 이미 취소된 주문은 cancel()이 내부에서 무시
public async handle(event: { orderId: string; reason: string }): Promise<void> {
  const order = await this.orderRepository.findOrders({ orderId: event.orderId, take: 1, page: 0 })
    .then((r) => r.orders.pop())
  if (!order) return  // 이미 삭제된 경우 무시
  if (order.status === 'cancelled') return  // 이미 처리된 경우 무시
  order.doSomething()
  await this.orderRepository.saveOrder(order)
}
```

**Level 2 예시 — Ledger:**

```typescript
// eventId를 키로 처리 기록을 남기고, 이미 있으면 skip
public async handle(event: { eventId: string; orderId: string }): Promise<void> {
  const alreadyProcessed = await this.eventLedger.check(event.eventId)
  if (alreadyProcessed) return

  await this.orderCommandService.doSomething({ orderId: event.orderId })
  await this.eventLedger.record(event.eventId)
}
```

**Level 1이 가능한 경우에는 Level 2를 쓰지 않는다.** Ledger 테이블 운영 비용이 발생한다.

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Domain Event 정의
- [cross-domain-communication.md](cross-domain-communication.md) — Integration Event vs Adapter 선택 기준
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
