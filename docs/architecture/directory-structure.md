# 디렉토리 구조

도메인 우선, 4레이어 구조를 따른다. 공용 인프라는 도메인 디렉토리 밖에 배치한다.

## 전체 구조

```
src/
  common/                              # 프로젝트 공통 유틸 (순수 함수)
    generate-id.ts                     # UUID 기반 고유 ID 생성
    is-unique-violation.ts             # DB unique 위반 판별

  database/                            # DB 연결 모듈
    transaction-manager.ts             # TransactionManager (AsyncLocalStorage 기반)

  outbox/                              # Outbox 모듈
    outbox-writer.ts                   # 트랜잭션 안에서 이벤트 저장 (Repository에서 호출)
    outbox-relay.ts                    # Outbox → 메시지 큐 전송 (폴링)
    event-consumer.ts                  # 메시지 큐 → EventHandler 수신 (폴링)
    event-handler-registry.ts          # eventType → Handler 라우팅

  task-queue/                          # Task Queue 모듈 (공용)
    task-queue.ts                      # TaskQueue 인터페이스 (abstract class)
    task-outbox-relay.ts               # task_outbox → 메시지 큐 발행 (폴링)
    task-consumer-registry.ts          # taskType → Handler 라우팅
    task-queue-consumer.ts             # 메시지 큐 → Task Controller 디스패치 (폴링)

  config/
    <concern>.config.ts                # 관심사별 설정 (database, jwt 등)
    config-validator.ts                # 환경 변수 검증 (Fail-fast)

  <domain>/
    domain/                            # 도메인 레이어 — 프레임워크 무의존
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <aggregate>-repository.ts        # Repository 인터페이스 (abstract class)
    application/
      adapter/
        <external-domain>-adapter.ts   # 외부 도메인 호출 인터페이스 (abstract class)
      service/
        <concern>-service.ts           # 기술 인프라 인터페이스 (abstract class)
      command/
        <domain>-command-service.ts    # Command Service (쓰기 — Repository 사용)
        <verb>-<noun>-command.ts
      query/
        <domain>-query-service.ts      # Query Service (읽기 — Query 인터페이스 사용)
        <domain>-query.ts              # Query 인터페이스 (abstract class)
        <verb>-<noun>-query.ts
        <verb>-<noun>-result.ts
      event/
        <domain-event>-handler.ts      # Domain Event Handler (application/event/)
      integration-event/
        <name>-integration-event.ts    # Integration Event 정의 (공개 계약)
    interface/
      <domain>-controller.ts           # HTTP Controller
      <domain>-task-controller.ts      # Task Consumer (메시지 큐 진입점)
      dto/
        <verb>-<noun>-request-body.ts
        <verb>-<noun>-request-param.ts
        <verb>-<noun>-response-body.ts
    infrastructure/
      <aggregate>-repository-impl.ts   # Repository 구현체
      <domain>-query-impl.ts           # Query 구현체 (읽기 전용 DB 접근)
      <external-domain>-adapter-impl.ts # Adapter 구현체
      <concern>-service-impl.ts        # 기술 인프라 Service 구현체
      <concern>-scheduler.ts           # Scheduler (Cron → TaskQueue.enqueue)
    <domain>-error-message.ts          # 에러 메시지 enum (모듈 루트)
    <domain>-enum.ts                   # 도메인 enum (모듈 루트)
    <domain>-constant.ts               # 도메인 상수 (모듈 루트)
```

---

## 레이어별 원칙

### domain/

- **프레임워크 무의존**: 어떤 외부 라이브러리도 import하지 않는다. 순수한 언어 코드.
- **비즈니스 규칙 캡슐화**: Aggregate Root 메서드 내부에서만 불변식을 검증한다.
- Repository는 **인터페이스(abstract class)만** 여기에 둔다. 구현체는 infrastructure/.

### application/

- 유스케이스 **조율자**. 비즈니스 로직을 직접 수행하지 않고 Aggregate에 위임한다.
- `command/`: Repository를 사용하는 쓰기 유스케이스.
- `query/`: Query 인터페이스를 사용하는 읽기 유스케이스.
- `adapter/`: 외부 도메인 호출 인터페이스 (Anticorruption Layer).
- `service/`: 기술 인프라 인터페이스 (암복호화, 파일 스토리지, 외부 API 등).
- `event/`: Domain Event Handler.
- `integration-event/`: Integration Event 정의 및 인바운드 처리.

### interface/

- 외부 진입점. HTTP Controller, Task Consumer, Integration Event Handler.
- 입력을 Command/Query로 변환하여 Application Service에 위임한다.
- **에러 변환은 여기에서만**: Application이 던진 plain Error를 HTTP/프로토콜 예외로 변환한다.

### infrastructure/

- 외부 시스템에 실제로 접근하는 유일한 레이어.
- Domain/Application의 abstract class 구현체가 모두 여기에 위치한다.
- ORM, 메시지 큐 SDK, 외부 API 클라이언트를 직접 사용한다.

---

## 파일 네이밍 규칙

모든 파일명은 `kebab-case`를 사용한다.

| 종류 | 위치 | 파일명 패턴 |
|------|------|------------|
| Aggregate Root | `domain/` | `<aggregate-root>.ts` (예: `order.ts`) |
| Entity | `domain/` | `<entity>.ts` (예: `order-item.ts`) |
| Value Object | `domain/` | `<value-object>.ts` (예: `money.ts`) |
| Domain Event | `domain/` | `<domain-event>.ts` (예: `order-cancelled.ts`) |
| Repository 인터페이스 | `domain/` | `<aggregate>-repository.ts` |
| Repository 구현체 | `infrastructure/` | `<aggregate>-repository-impl.ts` |
| Query 인터페이스 | `application/query/` | `<domain>-query.ts` |
| Query 구현체 | `infrastructure/` | `<domain>-query-impl.ts` |
| Command Service | `application/command/` | `<domain>-command-service.ts` |
| Query Service | `application/query/` | `<domain>-query-service.ts` |
| Command | `application/command/` | `<verb>-<noun>-command.ts` |
| Query DTO | `application/query/` | `<verb>-<noun>-query.ts` |
| Result | `application/query/` | `<verb>-<noun>-result.ts` |
| Domain Event Handler | `application/event/` | `<domain-event>-handler.ts` |
| Adapter 인터페이스 | `application/adapter/` | `<external-domain>-adapter.ts` |
| Adapter 구현체 | `infrastructure/` | `<external-domain>-adapter-impl.ts` |
| 기술 인프라 Service 인터페이스 | `application/service/` | `<concern>-service.ts` |
| 기술 인프라 Service 구현체 | `infrastructure/` | `<concern>-service-impl.ts` |
| HTTP Controller | `interface/` | `<domain>-controller.ts` |
| Task Controller | `interface/` | `<domain>-task-controller.ts` |
| Scheduler | `infrastructure/` | `<concern>-scheduler.ts` |
| 에러 메시지 enum | 모듈 루트 | `<domain>-error-message.ts` |
| 도메인 enum | 모듈 루트 | `<domain>-enum.ts` |
| 도메인 상수 | 모듈 루트 | `<domain>-constant.ts` |

---

## 클래스 네이밍 규칙

| 종류 | 규칙 | 예시 |
|------|------|------|
| Aggregate Root | 도메인 명사 (PascalCase) | `Order`, `User` |
| Entity | 도메인 명사 | `OrderItem`, `Address` |
| Value Object | 도메인 개념 | `Money`, `PhoneNumber` |
| Domain Event | 과거형 PascalCase | `OrderPlaced`, `OrderCancelled` |
| Repository 인터페이스 | `<Aggregate>Repository` | `OrderRepository` |
| Repository 구현체 | `<Aggregate>RepositoryImpl` | `OrderRepositoryImpl` |
| Query 인터페이스 | `<Domain>Query` | `OrderQuery` |
| Query 구현체 | `<Domain>QueryImpl` | `OrderQueryImpl` |
| Command | `<Verb><Noun>Command` | `CancelOrderCommand` |
| Query DTO | `<Verb><Noun>Query` | `GetOrdersQuery` |
| Result | `<Verb><Noun>Result` | `GetOrdersResult` |
| Adapter 인터페이스 | `<ExternalDomain>Adapter` | `PaymentAdapter` |
| Adapter 구현체 | `<ExternalDomain>AdapterImpl` | `PaymentAdapterImpl` |
| Error Message enum | `<Domain>ErrorMessage` | `OrderErrorMessage` |

---

## 공용 인프라 배치 기준

`domain/` 밖에 있는 공용 코드는 도메인에 속하지 않는 공유 인프라다.

| 디렉토리 | 포함 내용 |
|---------|----------|
| `common/` | 순수 유틸 함수 — ID 생성, DB 위반 판별 등. 프레임워크 무의존. |
| `database/` | DB 연결, TransactionManager — 모든 도메인 Repository에서 공유 |
| `outbox/` | OutboxWriter, OutboxPoller(Outbox → 메시지 큐 발행), OutboxConsumer(큐 수신 → Handler 실행), EventHandlerRegistry |
| `task-queue/` | TaskQueue 인터페이스/구현, Consumer, 멱등성 Ledger |
| `config/` | 환경 변수 로드·검증. 관심사별 설정 파일 분리. |

→ 프레임워크별 모듈 등록 방법은 `docs/implementations/` 참조

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향과 역할 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [domain-events.md](domain-events.md) — Outbox 구조
- [config.md](config.md) — 환경 설정 관리
