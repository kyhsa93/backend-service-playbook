# 스케줄링 / 배치 작업

주기적 작업과 배치 처리에 관한 원칙이다.

> **이 문서 범위**: 스케줄링의 프레임워크 무관 원칙과 패턴을 다룬다. 최소 요구사항 세 가지만 있으면 시작할 수 있고, 아래 상세 패턴은 대규모·다중 인스턴스·트랜잭션 정합성이 중요한 케이스의 참고 설계다.

---

## 최소 요구사항

1. **Scheduler는 Infrastructure 레이어에 배치**한다. 비즈니스 로직이 있는 Application 레이어에 두지 않는다.
2. **Task 핸들러는 멱등**하다. 메시지 큐는 at-least-once 전달이므로 동일 Task가 두 번 실행되어도 결과가 동일해야 한다.
3. **메시지 큐를 쓴다면 DLQ를 기본**으로 한다. 무한 재시도를 막고 독성 메시지를 격리한다.

---

## Scheduler 역할 분리

Scheduler는 **비즈니스 로직을 직접 실행하지 않는다**. Task를 큐에 적재(enqueue)하는 것만 한다. 실제 실행은 Task Consumer가 큐에서 메시지를 수신하여 Command Service를 호출한다.

```
[Scheduler] --(enqueue)--> [task_outbox] --(Relay)--> [메시지 큐] --(Consumer)--> [TaskController] --(호출)--> [CommandService]
```

**이유**:
- **다중 인스턴스 안전성**: 여러 인스턴스가 동시에 Cron을 실행해도, FIFO 큐의 deduplication으로 1건만 처리된다.
- **재시도 내장**: Consumer 실패 시 visibility timeout 경과 후 자동 재수신 → `maxReceiveCount` 초과 시 DLQ.
- **백프레셔**: 작업량이 폭증해도 큐에 쌓여 Consumer 처리 속도에 맞춰 소비된다.
- **관찰 가능성**: 큐 지표(메시지 수, 처리 지연, DLQ 수)로 배치 상태를 추적한다.

```typescript
// infrastructure/<concern>-scheduler.ts
class OrderCleanupScheduler {
  constructor(private readonly taskQueue: TaskQueue) {}

  // Cron 핸들러는 enqueue만 한다
  async enqueueDailyCleanup(): Promise<void> {
    const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
    await this.taskQueue.enqueue(
      'order.cleanup-expired',
      {},
      { groupId: 'order.cleanup', deduplicationId: dedupId }
    )
  }
}
```

---

## Task Outbox 패턴

Command Service에서 DB 변경과 Task 적재가 **원자적으로 묶여야 한다**. 메시지 큐에 직접 SendMessage를 호출하면 dual-write 문제가 발생한다 — DB는 commit됐는데 메시지 전송이 실패하거나, 메시지는 전송됐는데 DB가 롤백되는 불일치.

Domain Event의 Outbox 패턴과 동일한 이유로, **Task도 `task_outbox` 테이블 write → Relay 발행** 경로를 따른다.

```
Command 트랜잭션 내부:
  DB 변경 + task_outbox row insert
  → 트랜잭션 commit
  → TaskOutboxRelay가 폴링하여 메시지 큐에 발행
  → Task Consumer가 수신하여 실행
```

```typescript
// Application Service — DB 변경과 Task 적재가 같은 트랜잭션 안에서
await transactionManager.run(async () => {
  await orderRepository.saveOrder(order)
  await taskQueue.enqueue(
    'order.archive',
    { orderId: order.orderId },
    { groupId: order.orderId, deduplicationId: `order.archive-${order.orderId}` }
  )
})
```

Scheduler(Cron)처럼 트랜잭션 문맥이 없는 곳에서 호출할 때도 같은 경로를 사용한다. 단일 row insert이므로 자연스럽게 atomic이며, 경로가 통일되어 운영이 단순해진다.

---

## Task Controller — Interface 레이어

Task Controller는 **Interface 레이어의 입력 어댑터**다. HTTP Controller가 HTTP 요청을 받아 Application Service에 위임하듯, Task Controller는 메시지 큐 메시지를 받아 CommandService를 호출한다.

```
HTTP Controller   ← HTTP 요청    → CommandService
Task Controller   ← 메시지 큐    → CommandService
```

**원칙:**
- **로직 없이 Command 위임**: Task Controller는 CommandService의 메서드를 호출할 뿐이다. 조건 분기나 비즈니스 규칙을 넣지 않는다.
- **에러를 그대로 던진다**: HTTP Controller의 catch + 에러 변환 패턴을 쓰지 않는다. 예외는 Consumer가 catch하여 재시도/DLQ에 위임한다. 예외를 삼키면 실패가 소실된다.
- **DB 직접 접근 금지**: CommandService만 주입한다. 멱등성 Ledger 처리는 프레임워크에 위임한다.

```typescript
// interface/<domain>-task-controller.ts
class OrderTaskController {
  constructor(private readonly orderCommandService: OrderCommandService) {}

  async cleanupExpired(): Promise<void> {
    await this.orderCommandService.cleanupExpiredOrders()
  }

  async archive(payload: ArchiveOrderCommand): Promise<void> {
    await this.orderCommandService.archiveOrder(payload)  // 예외는 그대로 위로
  }
}
```

---

## MessageGroupId 전략

FIFO 큐에서 **같은 MessageGroupId를 가진 메시지는 순차 처리**된다. GroupId가 **병렬성의 경계**다.

| 상황 | groupId 설정 |
|------|-------------|
| Cron 전역 배치 (일 1회 등) | Task 카테고리: `'order.cleanup'` |
| Aggregate 단위 순차성 필요 | Aggregate ID: `orderId` |
| 순서 무관 + 고처리량 | 랜덤 UUID 또는 `taskType + random` |

같은 group은 직렬, 다른 group은 병렬. **필요한 최소 수준의 순차성만** groupId에 담는다.

---

## 멱등성

메시지 큐는 **at-least-once delivery**를 보장한다. 같은 Task가 두 번 이상 실행될 수 있다. Task 핸들러는 반드시 멱등하게 구현해야 한다.

3단계 전략은 [domain-events.md — 이벤트 핸들러 멱등성](domain-events.md#이벤트-핸들러-멱등성)과 동일한 모델을 따른다:

| 수준 | 상황 | 구현 방식 |
|------|------|----------|
| Level 1 — 본질적 멱등 | 반복 실행해도 결과가 동일 | 별도 장치 불필요 |
| Level 2 — Ledger | 부작용이 있는 핸들러 | 처리 기록을 DB에 저장, 중복 시 skip |
| Level 3 — 강한 원자성 | "성공한 경우에만 기록" 필요 | 핸들러 로직과 ledger를 같은 트랜잭션으로 묶음 |

```typescript
// Level 1 — 본질적 멱등: 만료 상태 기반으로 처리하므로 여러 번 실행해도 동일
async cleanupExpiredOrders(): Promise<void> {
  const { orders } = await orderRepository.findOrders({ status: ['expired'], take: 100, page: 0 })
  for (const order of orders) {
    order.archive()  // 이미 archive면 내부에서 무시
    await orderRepository.saveOrder(order)
  }
}
```

---

## Cron 다중 인스턴스 안전성

여러 인스턴스가 같은 시점에 Cron을 실행해도 **날짜 기반 deduplicationId**로 중복 적재를 방지한다.

```typescript
// 날짜 단위 dedupId — 5분 FIFO 중복 제거 윈도우 내에서 1건만 처리됨
const dedupId = `order.cleanup-expired-${new Date().toISOString().slice(0, 10)}`
await taskQueue.enqueue('order.cleanup-expired', {}, { groupId: 'order.cleanup', deduplicationId: dedupId })
```

같은 날 여러 인스턴스가 enqueue해도 `deduplicationId`가 동일하므로 FIFO 큐에 1건만 들어간다.

**주의**: Cron 핸들러에서 발생한 예외는 많은 스케줄링 라이브러리가 자동으로 삼킨다. **명시적 try-catch + 로깅**이 없으면 실패가 관찰 불가능해진다.

```typescript
async enqueueDailyCleanup(): Promise<void> {
  try {
    await this.taskQueue.enqueue(/* ... */)
  } catch (error) {
    logger.error({ message: 'Cron enqueue 실패', error })
    // 예외를 재throw하지 않아도 됨 — 다음 Cron tick에서 재시도
  }
}
```

---

## DLQ 모니터링

DLQ에 쌓인 메시지는 **코드 버그나 독성 페이로드의 증거**다.

- `maxReceiveCount` 초과 시 DLQ로 이동
- DLQ 메시지 수 > 0 알람 설정
- 원인 수정 후 DLQ → 원래 큐로 redrive

---

## payload 크기 제한

SQS는 단일 메시지 최대 256KB다.

- **작은 메타데이터만 payload에 담는다**: `{ orderId: 'o1' }` 수준.
- **대용량 데이터는 S3에 offload**하고 key만 담는다: `{ orderId: 'o1', payloadS3Key: 'tasks/abc.json' }`.

---

## 원칙 요약

- **Scheduler는 Infrastructure 레이어**: Application/Domain에 스케줄링 데코레이터 사용 금지.
- **Scheduler는 적재만**: Cron 핸들러는 `TaskQueue.enqueue`만 호출. 비즈니스 로직 직접 실행 금지.
- **Task Controller는 Interface 레이어**: HTTP Controller와 동일한 입력 어댑터. 에러를 그대로 던진다.
- **적재는 Outbox 경유**: DB 변경과 Task 적재의 원자성 보장. dual-write 문제 차단.
- **Command는 멱등하게**: at-least-once 전달이므로 반복 실행에도 결과가 동일해야 한다.
- **DLQ 필수**: 모든 Task 큐에 DLQ를 설정하고 알람으로 감시한다.
- **Cron 예외는 명시적 로깅**: 스케줄링 프레임워크가 예외를 삼키는 경우가 많으므로 직접 로깅한다.

---

### 관련 문서

- [domain-events.md](domain-events.md) — Task Queue vs Domain Event 구분, 멱등성 3단계
- [layer-architecture.md](layer-architecture.md) — 트랜잭션 전파 (AsyncLocalStorage)
- [graceful-shutdown.md](graceful-shutdown.md) — Consumer Graceful Shutdown
