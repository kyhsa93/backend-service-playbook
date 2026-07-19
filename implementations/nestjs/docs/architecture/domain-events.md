# 도메인 이벤트 발행 패턴

> **이 저장소의 실제 경로**: root [domain-events.md](../../../../docs/architecture/domain-events.md)가 규정하는 유일한 경로 — **Outbox 적재는 Repository.save() 트랜잭션 안에서, 큐 발행은 독립적으로 주기 실행되는 `OutboxPoller`가, EventHandler 실행은 SQS를 수신 대기하는 `OutboxConsumer`가** — 를 그대로 구현한다. Command Handler가 저장 직후 같은 프로세스 안에서 동기적으로 드레인하는 방식은 이 저장소에 없다. `src/outbox/outbox-poller.ts`/`outbox-consumer.ts`가 실제 코드이며, `EventHandlerRegistry`(`outbox/event-handler-registry.ts`)는 `Map<eventType, handler[]>` 기반으로 각 도메인 모듈의 `onModuleInit()`이 `register()`를 호출해 채운다 — 아래 예시에 나오는 `ModuleRef` 기반 동적 라우팅은 쓰지 않는다.

### 개념 구분 — Domain Event vs Integration Event

**Domain Event**: 같은 Bounded Context 내부 사건. Aggregate 내부 상태 변화의 결과. 구조가 자유롭게 변하며 외부 BC와 결합되지 않는다.
- 생성: Aggregate 도메인 메서드 내부에서 `_events.push(new OrderCancelled(...))`
- 저장: Repository에서 Outbox에 적재
- 수신: 같은 BC의 `application/event/<domain-event>-handler.ts` (`@HandleEvent`) — OutboxConsumer가 SQS에서 이 이벤트를 수신했을 때 호출된다

**Integration Event**: 외부 BC · 외부 시스템과의 **공개 계약**. 이름·스키마가 안정적이어야 하며 버전을 명시한다(`order.cancelled.v1`). 소비 측이 의존할 수 있는 유일한 접점.
- 생성: **Application EventHandler**가 Domain Event를 수신한 뒤 필요 시 변환하여 Outbox에 적재 (Aggregate가 직접 만들지 않는다)
- 수신: 외부 BC가 발행한 Integration Event는 같은 BC 입장에서 **외부 입력**이므로 `interface/integration-event/<domain>-integration-event-controller.ts`에서 수신 (HTTP Controller · Task Controller와 같은 Interface 입력 어댑터)

둘을 구분하지 않으면 BC 간 결합이 커지고 내부 이벤트 리팩토링이 외부 consumer를 깨뜨린다.

### 전체 흐름

```
[1. 도메인 로직 실행]
  Command Handler → Aggregate 도메인 메서드 호출 → Aggregate 내부에 Domain Event 객체 수집

[2. 저장 — 하나의 트랜잭션]
  Repository.save(aggregate) 내부에서:
    - Aggregate 상태 저장
    - aggregate.domainEvents를 outbox 테이블에 저장
    - aggregate.clearEvents()
  트랜잭션 커밋 → Aggregate와 이벤트가 함께 확정되거나 함께 롤백
  Command Handler는 여기서 끝난다 — processPending()류를 호출하지 않고 그대로 반환한다.

[3. OutboxPoller — 독립적으로 1초 주기 실행, Outbox → SQS 전송]
  outbox 테이블에서 processed=false인 행을 읽는다
    → 각 행을 SQS로 전송한다 (eventType은 MessageAttributes, payload는 MessageBody)
    → 전송에 성공한 행은 즉시 processed=true로 표시한다
      (processed는 이제 "핸들러가 처리를 끝냈다"가 아니라 "SQS로 전달을 끝냈다"는 뜻이다 —
       이후의 재시도/at-least-once 보장은 outbox가 아니라 SQS의 visibility timeout + DLQ가 담당한다)

[4. OutboxConsumer — 독립적으로 SQS를 long polling, EventHandler 호출]
  ReceiveMessageCommand(WaitTimeSeconds)로 수신 대기
    → 메시지를 받으면 eventType(MessageAttributes)으로 EventHandlerRegistry에서 핸들러를 찾아 호출
    → 핸들러 성공 → DeleteMessageCommand로 삭제(ack)
    → 핸들러 실패(또는 등록된 핸들러 없음) → 삭제하지 않음 → visibility timeout 이후 재수신(재시도)

[5. (선택) Integration Event 발행 — Application EventHandler가 변환]
  EventHandler가 Domain Event를 외부 BC로 알려야 할 때:
    → IntegrationEventV1 객체를 구성
    → OutboxWriter로 외부 BC용 outbox 행을 (같은 트랜잭션에서) 적재
    → 이후 3~4단계와 동일한 경로(OutboxPoller → SQS → OutboxConsumer)로 외부 BC에 전달된다

[6. 외부 BC의 Integration Event 수신 — Interface Integration Event Controller]
  다른 BC가 발행한 Integration Event가 자기 BC에 들어올 때:
    → interface/integration-event/<domain>-integration-event-controller.ts
    → @HandleIntegrationEvent('order.cancelled.v1') 메서드가 수신
    → Command Handler(CommandBus)를 호출하여 자기 도메인의 유스케이스 실행
```

**"같은 프로세스 안에서 저장 직후 동기적으로 드레인"하는 방식은 쓰지 않는다.** Command Handler가 저장 트랜잭션을 커밋한 뒤 곧바로 OutboxPoller/OutboxConsumer를 호출해 그 자리에서 이벤트를 처리해버리면, Outbox 패턴이 원래 분리하려던 "쓰기"와 "이벤트 처리"가 다시 한 요청 안에 묶여버린다. `harness/evaluators/rules/domain-event-outbox.evaluator.ts`가 Command Handler(`-command-handler.ts`)가 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 직접 참조하거나 `processPending()`류를 호출하면 `domain-event-outbox.command-handler.forbidden-sync-drain`으로 잡아낸다.

### 1단계: Aggregate에서 이벤트 수집

Aggregate의 도메인 메서드가 상태를 변경할 때 내부 `_events` 배열에 이벤트 객체를 추가한다.

```typescript
// domain/order.ts
export class Order {
  private readonly _events: OrderDomainEvent[] = []

  get domainEvents(): OrderDomainEvent[] { return [...this._events] }

  public cancel(reason: string): void {
    if (this._status === 'cancelled') throw new Error(...)
    this._status = 'cancelled'
    // 도메인 메서드 내부에서 이벤트 객체 생성
    this._events.push(new OrderCancelled({ orderId: this.orderId, reason, cancelledAt: new Date() }))
  }

  public clearEvents(): void { this._events.length = 0 }
}
```

### 2단계: Repository에서 Aggregate + Outbox를 트랜잭션으로 저장

**Repository 구현체의 save 메서드** 안에서 Aggregate 저장과 outbox 저장을 하나의 트랜잭션으로 묶는다. Command Handler는 outbox를 직접 다루지 않는다.

```typescript
// infrastructure/order-repository-impl.ts
@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    private readonly transactionManager: TransactionManager,
    private readonly outboxWriter: OutboxWriter
  ) {
    super()
  }

  public async saveOrder(order: Order): Promise<void> {
    const manager = this.transactionManager.getManager()
    // Aggregate 저장 + 이벤트 outbox 저장이 같은 트랜잭션
    await manager.save(OrderEntity, {
      orderId: order.orderId,
      userId: order.userId,
      status: order.status,
      items: order.items.map((i) => ({ ... }))
    })
    // 도메인 이벤트가 있으면 outbox에 저장
    if (order.domainEvents.length > 0) {
      await this.outboxWriter.saveAll(order.domainEvents)
      order.clearEvents()
    }
  }
}
```

Command Handler는 Repository.save()만 호출하면 된다 — 저장이 끝나면 그대로 반환한다:

```typescript
// application/command/cancel-order-command-handler.ts
@CommandHandler(CancelOrderCommand)
export class CancelOrderCommandHandler implements ICommandHandler<CancelOrderCommand> {
  constructor(
    private readonly orderRepository: OrderRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: CancelOrderCommand): Promise<void> {
    const order = await this.orderRepository.findOrders({ ... }).then((r) => r.orders.pop())
    if (!order) throw new Error(...)

    order.cancel(command.reason)                   // 도메인 메서드 → 이벤트 수집

    await this.transactionManager.run(async () => {
      await this.orderRepository.saveOrder(order)   // Aggregate + outbox 함께 저장
    })
    // 여기서 끝난다 — Outbox → SQS 발행과 EventHandler 실행은
    // OutboxPoller/OutboxConsumer가 독립적으로 처리한다.
  }
}
```

### Outbox Entity — 실제 코드

```typescript
// outbox/outbox.entity.ts
import { Column, CreateDateColumn, Entity, PrimaryColumn } from 'typeorm'

@Entity('outbox')
export class OutboxEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  eventId: string

  @Column()
  eventType: string

  @Column('text')
  payload: string

  @Column({ default: false })
  processed: boolean   // OutboxPoller가 SQS 발행을 마치면 true. 핸들러가 실제로 처리를
                        // 끝냈는지는 이 컬럼이 아니라 SQS가 안다(ack/재전달).

  @CreateDateColumn()
  createdAt: Date
}
```

### OutboxWriter — 실제 코드

트랜잭션 안에서 이벤트를 outbox 테이블에 저장한다. Repository 구현체 또는 `application/event/` EventHandler(Integration Event 발행 시)에서만 호출된다 — Command Handler 등 다른 Application 서브디렉토리에서 참조하면 `domain-event-outbox.command-service.outbox-writer-injection`으로 잡힌다.

```typescript
// outbox/outbox-writer.ts
@Injectable()
export class OutboxWriter {
  constructor(private readonly transactionManager: TransactionManager) {}

  public async saveAll(events: object[]): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.insert(OutboxEntity, events.map((event) => ({
      eventId: generateId(),
      eventType: (event as { eventName?: string }).eventName ?? event.constructor.name,
      payload: JSON.stringify(event),
      processed: false
    })))
  }
}
```

### 3단계: OutboxPoller — Outbox → SQS 전송 (실제 코드)

`src/outbox/outbox-poller.ts` — `@nestjs/schedule`의 `@Interval(1000)`으로 1초마다 단독 실행된다. Account/Payment가 각자 별도 Relay를 두던 옛 구조를 걷어내고, 이 하나의 Poller가 outbox 테이블 전체(모든 도메인)를 드레인한다 — Go/Java/Kotlin/FastAPI가 이미 쓰는 "단일 공유 outbox" 컨벤션과 일치시켰다.

```typescript
// outbox/outbox-poller.ts
@Injectable()
export class OutboxPoller {
  private readonly logger = new Logger(OutboxPoller.name)
  private isPolling = false   // 이전 tick이 안 끝났으면 겹쳐 실행하지 않는다

  constructor(
    private readonly transactionManager: TransactionManager,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  @Interval(1000)
  public async poll(): Promise<void> {
    if (this.isPolling) return
    this.isPolling = true
    try {
      await this.drainOnce()
    } catch (error) {
      this.logger.error({ message: 'Outbox 폴링 실패', error })
    } finally {
      this.isPolling = false
    }
  }

  private async drainOnce(): Promise<void> {
    const manager = this.transactionManager.getManager()
    const rows = await manager.find(OutboxEntity, {
      where: { processed: false }, order: { createdAt: 'ASC' }, take: 100
    })
    for (const row of rows) {
      try {
        await this.sqs.send(new SendMessageCommand({
          QueueUrl: getDomainEventQueueUrl(),
          MessageBody: row.payload,
          MessageAttributes: { eventType: { DataType: 'String', StringValue: row.eventType } }
        }))
        await manager.update(OutboxEntity, { eventId: row.eventId }, { processed: true })
      } catch (error) {
        this.logger.error({ message: 'SQS 발행 실패', event_type: row.eventType, event_id: row.eventId, error })
      }
    }
  }
}
```

**폴링 주기 선택**: 1초. `@nestjs/schedule`의 `@Interval`은 `@Cron`보다 "고정 간격으로 계속 실행"이라는 이 클래스의 목적에 더 직접적으로 대응한다. 겹쳐 실행 방지(`isPolling` 플래그)로 처리 시간이 1초를 넘는 경우에도 안전하다.

### 4단계: OutboxConsumer — SQS → EventHandler 수신 (실제 코드)

`src/outbox/outbox-consumer.ts` — `OnModuleInit`이 부트스트랩 시 단 한 번 백그라운드 루프를 시작한다(요청마다 새로 만들어지지 않는다). `ReceiveMessageCommand`의 `WaitTimeSeconds`로 long polling한다.

```typescript
// outbox/outbox-consumer.ts
@Injectable()
export class OutboxConsumer implements OnModuleInit, OnModuleDestroy {
  private running = false

  constructor(
    private readonly registry: EventHandlerRegistry,
    @Inject(SQS_CLIENT) private readonly sqs: SQSClient
  ) {}

  public onModuleInit(): void {
    this.running = true
    void this.pollLoop()
  }

  public onModuleDestroy(): void {
    this.running = false   // Graceful Shutdown 시 루프를 멈춘다
  }

  private async pollLoop(): Promise<void> {
    const queueUrl = getDomainEventQueueUrl()
    while (this.running) {
      const result = await this.sqs.send(new ReceiveMessageCommand({
        QueueUrl: queueUrl, MaxNumberOfMessages: 10,
        MessageAttributeNames: ['eventType'], WaitTimeSeconds: 5
      }))
      for (const message of result.Messages ?? []) {
        await this.handleMessage(queueUrl, message)
      }
    }
  }

  private async handleMessage(queueUrl: string, message: Message): Promise<void> {
    const eventType = message.MessageAttributes?.eventType?.StringValue
    try {
      if (!eventType) throw new Error('eventType 메시지 속성이 없습니다.')
      await this.registry.handle(eventType, JSON.parse(message.Body ?? '{}'))
      await this.sqs.send(new DeleteMessageCommand({ QueueUrl: queueUrl, ReceiptHandle: message.ReceiptHandle! }))
    } catch (error) {
      this.logger.error({ message: '이벤트 처리 실패', event_type: eventType, error })
      // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
    }
  }
}
```

### EventHandlerRegistry — 핸들러 라우팅 (실제 코드)

`eventType` 문자열을 핸들러 함수에 매핑하는 `Map` 기반 레지스트리다. `@HandleEvent`/`@HandleIntegrationEvent` 데코레이터는 위치 표식일 뿐이며, 실제 라우팅은 각 도메인 모듈의 `onModuleInit()`이 명시적으로 호출하는 `register()`로 구성한다(`ModuleRef` 기반 동적 조회는 쓰지 않는다).

```typescript
// outbox/event-handler-registry.ts
type EventHandlerFn = (payload: object) => Promise<void>

@Injectable()
export class EventHandlerRegistry {
  private readonly handlers = new Map<string, EventHandlerFn[]>()

  public register(eventType: string, handler: EventHandlerFn): void {
    const list = this.handlers.get(eventType) ?? []
    list.push(handler)
    this.handlers.set(eventType, list)
  }

  public async handle(eventType: string, payload: object): Promise<void> {
    for (const handler of this.handlers.get(eventType) ?? []) {
      await handler(payload)
    }
  }
}
```

각 도메인 모듈이 자기 도메인의 Domain Event Handler와, 다른 BC가 발행하는 Integration Event 수신부를 이 레지스트리 하나에 등록한다 — 예전에는 도메인마다 별도 `OutboxRelay`가 생성자 주입 고정 맵으로 이 라우팅을 담당했다.

```typescript
// account/account-module.ts — 실제 코드(발췌)
export class AccountModule implements OnModuleInit {
  constructor(
    private readonly registry: EventHandlerRegistry,
    private readonly accountIntegrationEventController: AccountIntegrationEventController,
    private readonly accountCreatedHandler: AccountCreatedHandler,
    // ...나머지 Domain Event Handler들
  ) {}

  onModuleInit(): void {
    this.registry.register('AccountCreated', (payload) => this.accountCreatedHandler.handle(payload as never))
    // ...나머지 Domain Event 등록
    this.registry.register('payment.completed.v1', (payload) =>
      this.accountIntegrationEventController.onPaymentCompleted(payload as never))
    // ...나머지 Integration Event 등록
  }
}
```

### EventHandler — 도메인 이벤트 수신 및 처리

```typescript
// application/event/order-cancelled-handler.ts
@Injectable()
export class OrderCancelledHandler {
  private readonly logger = new Logger(OrderCancelledHandler.name)

  @HandleEvent('OrderCancelled')
  public async handle(event: { orderId: string; reason: string }): Promise<void> {
    this.logger.log({ message: '주문 취소 이벤트 수신', order_id: event.orderId })
    // 후속 처리: 환불 요청, 알림 발송, 재고 복원 등
  }
}
```

### 디렉토리 구조 — 실제 코드

```
src/
  outbox/
    outbox-module.ts                 ← OutboxModule (@Global)
    outbox.entity.ts                 ← Outbox 테이블 Entity
    outbox-writer.ts                 ← 트랜잭션 안에서 이벤트 저장 (Repository · Application EventHandler에서 호출)
    outbox-poller.ts                 ← Outbox → SQS 전송 (@Interval(1000))
    outbox-consumer.ts                ← SQS → EventHandlerRegistry 라우팅 (long polling)
    sqs-client-provider.ts           ← SQSClient 생성 (SES/Secrets Manager 클라이언트와 동일한 구성)
    event-handler-registry.ts        ← eventType → Handler 라우팅 (@HandleEvent · @HandleIntegrationEvent 데코레이터 + Map)
  <domain>/
    domain/
      <domain-event>.ts              ← Domain Event 정의 (내부용, 버저닝 없음)
    application/
      event/
        <domain-event>-handler.ts    ← Domain EventHandler (@HandleEvent)
      integration-event/
        <name>-integration-event.ts  ← Integration Event 정의 (외부 공개 계약, V1 등 버전)
    interface/
      integration-event/
        <domain>-integration-event-controller.ts  ← 외부 BC Integration Event 수신 (@HandleIntegrationEvent)
```

### Module 등록 — 실제 코드

```typescript
// outbox/outbox-module.ts
@Global()
@Module({
  imports: [TypeOrmModule.forFeature([OutboxEntity])],
  providers: [TransactionManager, OutboxWriter, EventHandlerRegistry, SqsClientProvider, OutboxPoller, OutboxConsumer],
  exports: [TransactionManager, OutboxWriter, EventHandlerRegistry]
})
export class OutboxModule {}
```

`OutboxPoller`/`OutboxConsumer`는 `providers`에만 있고 `exports`에는 없다 — 다른 모듈이 이들을 주입받아 직접 호출할 수 없어야 한다. `@Global()` 모듈의 provider이므로 앱 부트스트랩 시 싱글턴으로 한 번만 생성되고, `OutboxConsumer.onModuleInit()`이 그 시점에 백그라운드 루프를 시작한다 — `AppModule`이 `ScheduleModule.forRoot()`를 import해야 `@Interval`이 동작한다.

```typescript
// app-module.ts (발췌)
@Module({
  imports: [
    ScheduleModule.forRoot(),   // OutboxPoller의 @Interval 활성화
    // ...
    OutboxModule,
    AccountModule,
    CardModule,
    PaymentModule
  ]
})
export class AppModule {}
```

### SQS 클라이언트 — 실제 코드

기존 SES/Secrets Manager 클라이언트 구성(`AWS_ENDPOINT_URL` 분기, 정적 test 자격증명)을 그대로 따른다.

```typescript
// outbox/sqs-client-provider.ts
export const SQS_CLIENT = Symbol('SQS_CLIENT')

export function createSqsClient(): SQSClient {
  return new SQSClient({
    region: getAwsRegion(),
    endpoint: getAwsEndpoint(),
    credentials: getAwsCredentials()
  })
}

export const SqsClientProvider: FactoryProvider<SQSClient> = {
  provide: SQS_CLIENT,
  useFactory: createSqsClient
}
```

큐 URL은 `config/aws.config.ts`의 `getDomainEventQueueUrl()`이 `SQS_DOMAIN_EVENT_QUEUE_URL` 환경 변수에서 읽는다.

### LocalStack + Docker Compose — 실제 코드

```yaml
# docker-compose.yml (발췌)
localstack:
  environment:
    SERVICES: ses,secretsmanager,sqs
```

```bash
# localstack/init-sqs.sh — DLQ 우선 생성 후 RedrivePolicy로 메인 큐에 연결
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url .../domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

```env
# .env.development — SQS 큐 URL 추가
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

DLQ + `maxReceiveCount=3`은 [scheduling.md — DLQ 모니터링](../../../../docs/architecture/scheduling.md#dlq-모니터링)이 요구하는 컨벤션을 그대로 따른다.

### 이벤트 핸들러 멱등성

SQS는 at-least-once 전달을 보장한다. 같은 메시지가 **중복 수신**될 수 있으므로 모든 EventHandler는 **멱등(idempotent)** 하게 구현해야 한다.

> **참고**: Task Queue의 `@TaskConsumer`에도 동일한 at-least-once 전제가 적용되며, 그쪽은 **프레임워크 레벨 ledger(`idempotencyKey` 옵션)**·**Level 3 강한 원자성** 등 3단계 모델을 제공한다. EventHandler와 Task Controller 모두에 공통되는 패턴이며 자세한 구조는 [scheduling.md — 멱등성](./scheduling.md#멱등성)을 참조한다. EventHandler도 부작용 큰 경우 (재결제·외부 API 호출 등) 동일한 ledger 전략 적용을 권장한다. 이 저장소의 `DepositByPaymentCommandHandler`/`WithdrawByPaymentCommandHandler`는 `referenceId` 기준 Level 2 Ledger(`hasTransactionWithReference`)를 실제로 쓴다.

```typescript
// 올바른 방식 — 멱등한 핸들러
@HandleEvent('OrderCancelled')
public async handle(event: { orderId: string }): Promise<void> {
  const refund = await this.refundRepository
    .findRefunds({ orderId: event.orderId, take: 1, page: 0 })
    .then((r) => r.refunds.pop())
  if (refund) return  // 이미 처리됨 — 중복 실행 방지
  await this.refundRepository.saveRefund(new Refund({ orderId: event.orderId, ... }))
}

// 잘못된 방식 — 멱등하지 않은 핸들러
@HandleEvent('OrderCancelled')
public async handle(event: { orderId: string }): Promise<void> {
  await this.refundRepository.saveRefund(new Refund({ orderId: event.orderId, ... }))
}
```

### Integration Event 정의 — 외부 BC용 공개 계약

Integration Event는 **application/integration-event/**에 정의한다. Domain Event와 분리된 공개 계약이므로 이름에 **버전 접미사(V1 등)**를 붙이고 스키마는 의도적으로 평탄하게 설계한다 (내부 Aggregate 구조 노출 금지).

```typescript
// order/application/integration-event/order-cancelled-integration-event.ts
export class OrderCancelledIntegrationEventV1 {
  public readonly eventName = 'order.cancelled.v1' as const
  constructor(
    public readonly orderId: string,
    public readonly cancelledAt: string,
    public readonly reason: string
  ) {}
}
```

파일·클래스 네이밍:
- 파일: `<name>-integration-event.ts` (application/integration-event/)
- 클래스: `<Name>IntegrationEventV1`, 스키마 변경 시 `V2` 추가 (V1은 호환 유지 기간 동안 함께 발행)
- eventName 리터럴: `<domain>.<verb-past>.v<N>` — SQS `MessageAttributes.eventType`으로 사용

### Integration Event 발행 — Application EventHandler

같은 BC의 Domain Event를 수신한 EventHandler가 외부 BC에 알릴 필요가 있을 때 Integration Event를 구성하여 Outbox에 적재한다. **EventHandler는 Application 레이어에서 OutboxWriter를 직접 사용할 수 있는 유일한 예외**이다 (Command Handler는 여전히 금지).

```typescript
// order/application/event/order-cancelled-handler.ts
@Injectable()
export class OrderCancelledHandler {
  constructor(private readonly outboxWriter: OutboxWriter) {}

  @HandleEvent('OrderCancelled')
  public async handle(event: { orderId: string; reason: string; cancelledAt: string }): Promise<void> {
    // 같은 BC 내 후속 처리가 있다면 여기서 수행 (Command Handler 호출 등)

    // 외부 BC에 알리는 경우 Integration Event로 변환하여 발행
    await this.outboxWriter.saveAll([
      new OrderCancelledIntegrationEventV1(event.orderId, event.cancelledAt, event.reason)
    ])
  }
}
```

> Domain Event 자체를 그대로 외부에 전달하지 않는다. 내부 스키마 변경이 외부 consumer를 깨뜨린다. EventHandler가 변환 지점이다.

### Integration Event 수신 — Interface Integration Event Controller

외부 BC가 발행한 Integration Event가 자기 BC에 도착하면 **Interface 레이어의 Integration Event Controller**에서 받는다. HTTP Controller · Task Controller와 같은 입력 어댑터로 취급한다.

```typescript
// card/interface/integration-event/card-integration-event-controller.ts — 실제 코드
@Injectable()
export class CardIntegrationEventController {
  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('account.suspended.v1')
  public async onAccountSuspended(event: { accountId: string }): Promise<void> {
    await this.commandBus.execute(new SuspendCardsByAccountCommand({ accountId: event.accountId }))
  }
}
```

파일·클래스 네이밍:
- 파일: `<domain>-integration-event-controller.ts` (interface/integration-event/)
- 클래스: `<Domain>IntegrationEventController`
- 데코레이터: `@HandleIntegrationEvent('<event-name>.v<N>')` — Domain Event용 `@HandleEvent`와 구분

Task Controller와 동일하게 예외는 그대로 throw하여 OutboxConsumer가 메시지를 삭제하지 않고 재시도(→ DLQ)를 담당하게 한다. Controller에서 `generateErrorResponse`를 쓰지 않는다.

### 원칙

- **Domain Event는 Aggregate 내부에서만 생성한다**: 도메인 메서드가 이벤트 객체를 `_events`에 추가한다.
- **Integration Event는 Aggregate가 직접 만들지 않는다**: Application EventHandler가 Domain Event를 변환하여 Outbox에 적재한다.
- **Repository.save()에서 Aggregate + Outbox를 함께 저장한다**: Command Handler는 outbox를 직접 다루지 않는다.
- **Command Handler는 저장 후 곧바로 반환한다**: OutboxRelay/OutboxPoller/OutboxConsumer를 직접 호출하지 않는다 — 동기 드레인은 harness가 `domain-event-outbox.command-handler.forbidden-sync-drain`으로 잡아낸다.
- **Outbox → SQS → Handler 순서로 전달한다**: OutboxPoller(발행)와 OutboxConsumer(수신·실행)가 서로 독립적으로 주기 실행된다.
- **Handler는 멱등하게 구현한다**: SQS at-least-once 전달 특성에 대비한다.
- **Domain Event Handler는 application/event/에 배치한다**: `@HandleEvent` 데코레이터로 eventType을 지정하고, 각 도메인 모듈의 `onModuleInit()`에서 `EventHandlerRegistry.register()`로 등록한다.
- **Integration Event Controller는 interface/integration-event/에 배치한다**: `@HandleIntegrationEvent`로 버전이 포함된 이벤트명을 지정하고, CommandBus를 호출해 자기 BC의 유스케이스만 실행한다.
- **Integration Event는 버저닝한다**: `V1`/`order.cancelled.v1` 식으로 공개 계약을 명시하고 호환 유지 기간 동안 구·신 버전을 함께 발행한다.
