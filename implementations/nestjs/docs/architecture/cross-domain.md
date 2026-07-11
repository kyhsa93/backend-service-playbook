# 크로스 도메인 호출 패턴

BC 간 통신은 **동기(Adapter/ACL)** 와 **비동기(Integration Event)** 두 가지가 있다. 선택 기준은 루트 [cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md)를 따른다. 이 문서는 그 두 패턴이 `examples/`에서 **실제로 동작하는 코드**로 어떻게 구현되어 있는지 NestJS 관점에서 설명한다.

`examples/`에는 두 개의 Bounded Context가 있다.

- **Account BC** (`src/account/`) — 계좌 개설·입출금·정지·해지. 상류(Supplier).
- **Card BC** (`src/card/`) — 카드 발급. 계좌에 종속된 하류(Customer). Account와 **Customer-Supplier** 관계다.

Card는 두 방향으로 Account에 의존한다.

| 방향 | 필요 | 패턴 | 구현 위치 |
|------|------|------|----------|
| 카드 발급 시 연결 계좌가 활성인지 **즉시 조회** | 응답에 필요 | 동기 **Adapter(ACL)** | `card/application/adapter/`, `card/infrastructure/account-adapter-impl.ts` |
| 계좌가 정지/해지되면 카드도 **정지/해지** | 최종 일관성 허용 | 비동기 **Integration Event** | Account가 발행 → Outbox → `card/interface/integration-event/` 수신 |

Account는 Card를 **전혀 import하지 않는다**(의존 방향은 Card → Account 단방향). 비동기 전달은 Outbox와 `EventHandlerRegistry`가 두 BC를 느슨하게 연결한다.

---

## 1. 동기 — Adapter 패턴 (ACL)

카드 발급은 "연결 계좌가 존재하고 활성인가?"의 응답이 현재 요청 처리에 필요하므로 동기 조회다.

### 원칙

1. **Application(Command Handler)에서 Adapter 인터페이스를 통해서만 외부 도메인을 호출**한다. 외부 도메인의 Repository/도메인 객체를 직접 주입하지 않는다.
2. **Adapter 인터페이스는 호출하는 쪽(`card/application/adapter/`)에** abstract class로 정의한다.
3. **Adapter 구현체는 호출하는 쪽(`card/infrastructure/`)에** 배치하고, 외부 도메인 모듈이 `exports`한 읽기 서비스(`AccountQuery`)를 주입받아 호출한다.
4. **ACL은 상류 모델을 번역**한다. Account의 `AccountStatus` enum을 그대로 노출하지 않고 Card가 필요로 하는 최소 형태(`{ accountId, active }`)로 바꾸며, "계좌 없음" 에러를 `null`로 번역한다.

### 실제 코드

```typescript
// card/application/adapter/account-adapter.ts — 인터페이스 (abstract class)
export abstract class AccountAdapter {
  abstract findAccount(query: {
    readonly accountId: string
    readonly ownerId: string
  }): Promise<{ accountId: string; active: boolean } | null>
}
```

```typescript
// card/infrastructure/account-adapter-impl.ts — 구현체(ACL)
@Injectable()
export class AccountAdapterImpl extends AccountAdapter {
  constructor(private readonly accountQuery: AccountQuery) { super() }  // AccountModule이 export한 읽기 서비스

  public async findAccount(query: { accountId: string; ownerId: string }) {
    try {
      const account = await this.accountQuery.getAccount({ accountId: query.accountId, ownerId: query.ownerId })
      return { accountId: account.accountId, active: account.status === AccountStatus.ACTIVE }  // 상류 모델을 번역
    } catch (error) {
      if (error instanceof Error && error.message === AccountErrorMessage['계좌를 찾을 수 없습니다.']) return null
      throw error
    }
  }
}
```

```typescript
// card/application/command/issue-card-command-handler.ts — Adapter를 통해 동기 조회
const account = await this.accountAdapter.findAccount({ accountId: command.accountId, ownerId: command.requesterId })
if (!account) throw new Error(ErrorMessage['연결할 계좌를 찾을 수 없습니다.'])
if (!account.active) throw new Error(ErrorMessage['활성 상태의 계좌만 카드를 발급할 수 있습니다.'])

const card = Card.issue({ accountId: command.accountId, ownerId: command.requesterId, brand: command.brand })
await this.transactionManager.run(async () => { await this.cardRepository.saveCard(card) })
```

`AccountModule`은 `exports: [AccountQuery]`로 **읽기 서비스만** 공개한다. Repository·도메인 객체는 공개하지 않는다. `CardModule`은 `imports: [AccountModule]`로 이를 주입받아 `AccountAdapterImpl`에 연결한다.

> **주의**: Adapter를 통해 외부 BC의 **쓰기 메서드**를 호출하지 않는다. 쓰기가 필요하면 Integration Event로 전환한다(아래 2절).

---

## 2. 비동기 — Integration Event

계좌가 정지/해지되면 카드도 정지/해지되어야 하지만, 이는 **상태 변경**이고 최종 일관성이 허용되므로 두 BC를 하나의 트랜잭션으로 묶지 않는다. Account가 Integration Event를 발행하고 Card가 독립적으로 반응한다.

### 전체 흐름 (실제 코드 기준)

```
[Account] suspend 커맨드
  → Account.suspend() 가 AccountSuspended Domain Event 수집
  → Repository.saveAccount() 가 Outbox에 적재 (eventType="AccountSuspended")
  → OutboxRelay.processPending() 드레인:
       패스1: AccountSuspendedHandler(application/event/) 가
              AccountSuspendedIntegrationEventV1 로 변환해 Outbox에 적재
              (eventType="account.suspended.v1")
       패스2: relay 정적 맵에 없는 eventType → EventHandlerRegistry 로 위임
              → CardIntegrationEventController.onAccountSuspended (interface/integration-event/)
              → SuspendCardsByAccountCommand → ACTIVE 카드들을 SUSPENDED로
```

핵심 파일:

| 역할 | 파일 |
|------|------|
| Integration Event 정의(공개 계약) | `account/application/integration-event/account-suspended-integration-event.ts` (`account.suspended.v1`) |
| Domain Event → Integration Event 변환 | `account/application/event/account-suspended-handler.ts` (`@HandleEvent`) |
| Outbox 드레인 + 라우팅 | `account/application/event/outbox-relay.ts` + `outbox/event-handler-registry.ts` |
| 외부 BC 수신부 | `card/interface/integration-event/card-integration-event-controller.ts` (`@HandleIntegrationEvent`) |
| 반응 유스케이스 | `card/application/command/suspend-cards-by-account-command-handler.ts` |

### Integration Event 정의 — 버전이 명시된 공개 계약

```typescript
// account/application/integration-event/account-suspended-integration-event.ts
export class AccountSuspendedIntegrationEventV1 {
  public readonly eventName = 'account.suspended.v1' as const  // Outbox row의 eventType으로 사용
  constructor(public readonly accountId: string, public readonly suspendedAt: string) {}
}
```

내부 `AccountSuspended` Domain Event(스키마 자유롭게 변함)와 분리된 **외부 공개 계약**이다. `OutboxWriter`는 `eventName`이 있으면 그것을(없으면 클래스명을) `eventType`으로 적재한다.

### 변환은 Application EventHandler에서만

```typescript
// account/application/event/account-suspended-handler.ts
@HandleEvent('AccountSuspended')
public async handle(event: { accountId: string; email: string; suspendedAt: string }): Promise<void> {
  await this.outboxWriter.saveAll([
    new AccountSuspendedIntegrationEventV1(event.accountId, event.suspendedAt ?? new Date().toISOString())
  ])
  // ...알림 등 같은 BC 내 후속 처리
}
```

Aggregate는 Integration Event를 직접 만들지 않는다. `application/event/`의 EventHandler가 **유일한 변환 지점**이며, Application 레이어에서 `OutboxWriter`를 직접 쓸 수 있는 유일한 예외다(Command Handler는 금지).

### 수신은 Interface Integration Event Controller에서

```typescript
// card/interface/integration-event/card-integration-event-controller.ts
@Injectable()
export class CardIntegrationEventController {
  constructor(private readonly commandBus: CommandBus) {}

  @HandleIntegrationEvent('account.suspended.v1')
  public async onAccountSuspended(event: { accountId: string }): Promise<void> {
    await this.commandBus.execute(new SuspendCardsByAccountCommand({ accountId: event.accountId }))
  }
}
```

HTTP Controller·Task Controller와 같은 **Interface 입력 어댑터**다. 자기 BC의 Command만 호출하고, 예외는 그대로 throw하여 relay가 재시도하게 한다.

### 라우팅 — 발행 BC가 수신 BC를 import하지 않는 이유

이 저장소의 Outbox는 SQS 홉 없이 **같은 프로세스 안에서 동기적으로** 드레인된다([domain-events.md](domain-events.md) 참조). `OutboxRelay`는 자기 BC(Account)의 Domain Event는 생성자 주입 기반 **정적 맵**으로 처리하고, 맵에 없는 eventType(다른 BC가 발행한 Integration Event)은 **`EventHandlerRegistry`로 위임**한다.

```typescript
// account/application/event/outbox-relay.ts (핵심)
const handler = this.handlers[row.eventType]
if (handler) await handler(payload)                 // 자기 BC Domain Event (정적 맵)
else await this.registry.handle(row.eventType, payload)  // 다른 BC Integration Event (레지스트리)
```

수신 BC는 자기 모듈의 `onModuleInit`에서 수신부를 등록한다.

```typescript
// card/card-module.ts
onModuleInit(): void {
  this.registry.register('account.suspended.v1', (p) => this.cardIntegrationEventController.onAccountSuspended(p as never))
  this.registry.register('account.closed.v1', (p) => this.cardIntegrationEventController.onAccountClosed(p as never))
}
```

이렇게 하면 **Account는 Card를 import하지 않고도** Card에 이벤트를 전달한다. 발행 측과 수신 측의 유일한 접점은 버전이 명시된 이벤트명(`account.suspended.v1`)이다.

`processPending()`은 드레인 도중 적재된 새 행(Domain Event → Integration Event)을 같은 커맨드 처리 안에서 이어서 드레인하도록 **더 진전이 없을 때까지 여러 패스로 반복**한다.

### 멱등성

Integration Event는 at-least-once 전달을 전제로 하므로 수신 측 유스케이스는 **멱등**해야 한다. `SuspendCardsByAccountCommandHandler`는 `ACTIVE` 카드만 골라 정지하므로, 같은 이벤트가 재수신되어도(이미 정지된 카드) 아무 일도 하지 않는다.

---

## Context Map 패턴과의 대응

| Context Map 패턴 | 이 저장소의 구현 |
|----------------|------------------|
| ACL (Anticorruption Layer) | `AccountAdapter` + `AccountAdapterImpl` — 상류 `AccountStatus`/에러를 번역 |
| Customer-Supplier | Card(하류)가 Adapter로 조회 + Integration Event로 반응 |
| OHS/PL (Published Language) | `account.suspended.v1` / `account.closed.v1` 버전 명시 |

## 관련 문서

- [../../../../docs/architecture/cross-domain-communication.md](../../../../docs/architecture/cross-domain-communication.md) — 동기 vs 비동기 선택 기준(프레임워크 무관)
- [domain-events.md](domain-events.md) — Outbox·Integration Event 발행/수신 상세
- [module-pattern.md](module-pattern.md) — 모듈 간 의존, `exports`
