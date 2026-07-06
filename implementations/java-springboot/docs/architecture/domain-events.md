# 도메인 이벤트 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md) 참고.

## Outbox 패턴 — 구현 완료

root 원칙: Domain Event는 in-process 이벤트 버스를 사용하지 않는다. **Repository가 Aggregate와 이벤트를 같은 트랜잭션에서 Outbox 테이블에 저장 → Relay가 저장된 이벤트를 읽어 핸들러에 전달**하는 경로를 따른다.

이 저장소의 `examples/`는 이 경로를 그대로 구현한다 — 단, root/nestjs 문서가 언급하는 "메시지 큐 + `@Scheduled` 폴링" 대신, **Command Service가 자신의 저장 트랜잭션이 커밋된 직후 Relay를 동기적으로 한 번 호출**하는 방식을 쓴다. 이 저장소에는 실제 메시지 큐 인프라가 없고, e2e 테스트에서 비동기 폴링을 기다리는 타이밍 문제를 피하려는 의도적 단순화다 — nestjs 구현체와 동일한 트리거 전략이다.

```java
// application/command/CreateAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
public class CreateAccountService {

    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.save(account);      // Account + Outbox 행을 한 트랜잭션으로 커밋
        outboxRelay.processPending();          // 커밋 직후, 테이블 전체를 드레인
        return new CreateAccountResult(/* ... */);
    }
}
```

`CreateAccountService`는 더 이상 `@Transactional`을 클래스에 붙이지 않는다 — 유일한 물리 트랜잭션 경계는 `AccountRepositoryImpl.save()`(아래 2단계) 내부에 있다. `save()`가 반환(=커밋)한 뒤에야 `outboxRelay.processPending()`을 호출하므로, Relay는 항상 이미 커밋된 이벤트만 읽는다 — "발행 직후 크래시하면 유실"이라는 예전 gap이 사라진다: 크래시가 나더라도 Outbox 행은 DB에 남아 있고, 다음에 어떤 커맨드든 호출되면 `processPending()`이 테이블 전체를 다시 훑어 재시도한다.

---

## 1단계: Aggregate에서 이벤트 수집 (변경 없음)

```java
// domain/Account.java — 실제 코드
@Transient
private final List<Object> domainEvents = new ArrayList<>();

public Transaction deposit(long amount) {
    // ... 불변식 검증 ...
    this.domainEvents.add(new MoneyDepositedEvent(
            this.accountId, this.email, transaction.getTransactionId(), money, this.balance, transaction.getCreatedAt()));
    return transaction;
}

public List<Object> pullDomainEvents() {
    List<Object> events = new ArrayList<>(this.domainEvents);
    this.domainEvents.clear();
    return events;
}
```

`@Transient`가 이 필드를 JPA 컬럼 매핑에서 제외한다 — 이벤트는 영속 상태가 아니라 "아직 전달되지 않은 사실의 목록"이다. 이 수집 패턴은 Outbox 도입 전후로 전혀 바뀌지 않았다.

---

## 2단계: Repository가 Aggregate + Outbox를 한 트랜잭션으로 저장

```java
// outbox/OutboxEvent.java — 실제 코드
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @Column(length = 32, nullable = false, updatable = false)
    private String eventId;                    // UUID.randomUUID() 하이픈 제거, 32자리 hex

    @Column(nullable = false, updatable = false)
    private String eventType;                  // 도메인 이벤트 record의 simple name, 예: "AccountCreatedEvent"

    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;                     // ObjectMapper로 직렬화한 JSON

    @Column(nullable = false)
    private boolean processed = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ... create() 정적 팩토리, markProcessed(), getter ...
}
```

```java
// outbox/OutboxWriter.java — 실제 코드
@Component
@RequiredArgsConstructor
public class OutboxWriter {
    private final OutboxEventJpaRepository outboxJpaRepository;
    private final ObjectMapper objectMapper;

    public void saveAll(List<Object> events) {
        if (events.isEmpty()) return;
        List<OutboxEvent> outboxEvents = events.stream().map(this::toOutboxEvent).toList();
        outboxJpaRepository.saveAll(outboxEvents);
    }

    private OutboxEvent toOutboxEvent(Object event) {
        try {
            return OutboxEvent.create(event.getClass().getSimpleName(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패: " + event.getClass().getSimpleName(), e);
        }
    }
}
```

```java
// account/infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    @Transactional   // Account + Transaction + Outbox 저장이 하나의 물리 트랜잭션
    public void save(Account account) {
        jpaRepository.save(account);
        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending);
        }
        outboxWriter.saveAll(account.pullDomainEvents());
    }
}
```

**Command Service는 더 이상 `ApplicationEventPublisher`/`@EventListener`를 쓰지 않는다.** 6개 Command Service(`CreateAccountService`/`DepositService`/`WithdrawService`/`SuspendAccountService`/`ReactivateAccountService`/`CloseAccountService`) 모두 `ApplicationEventPublisher` 의존성을 제거했고, 클래스 레벨 `@Transactional`도 제거했다 — 유일한 물리 트랜잭션 경계는 `AccountRepositoryImpl.save()`다. 이 한 번의 호출로 Aggregate 상태와 이벤트가 원자적으로 커밋되거나 함께 롤백된다.

---

## 3단계: OutboxRelay — 커밋 직후 동기 드레인

```java
// outbox/OutboxRelay.java — 실제 코드
@Component
@RequiredArgsConstructor
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final List<OutboxEventHandler> eventHandlers;

    @Transactional
    public void processPending() {
        Map<String, OutboxEventHandler> handlers = eventHandlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
        List<OutboxEvent> pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                OutboxEventHandler handler = handlers.get(event.getEventType());
                if (handler != null) {
                    handler.handle(event.getPayload());
                }
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("이벤트 처리 실패: eventType={}, eventId={}", event.getEventType(), event.getEventId(), e);
                // processed가 갱신되지 않으므로 다음 processPending() 호출에서 재시도된다
            }
        }
    }
}
```

`processPending()`은 **`@Scheduled` 폴링이 아니라, 모든 Command Service가 자신의 저장 트랜잭션 직후 한 번씩 호출한다** — `CreateAccountService`뿐 아니라 `DepositService`/`WithdrawService`/`SuspendAccountService`/`ReactivateAccountService`/`CloseAccountService`도 동일하게 `accountRepository.save(account)` 다음 줄에서 호출한다. 매 호출마다 테이블 전체의 미처리 행을 훑으므로, 특정 커맨드가 남긴 이벤트가 그 커맨드 자신의 호출에서 실패하더라도 **다른 어떤 커맨드가 다음에 호출되든** 다시 시도된다 — 별도의 재시도 스케줄러가 없어도 at-least-once 전달이 성립한다.

핸들러 실행 중 예외가 발생해도 `catch`가 흡수하므로 `processPending()`을 호출한 원본 커맨드(계좌 생성/입출금 등)는 절대 실패하지 않는다 — 알림 발송 실패가 핵심 비즈니스 트랜잭션을 오염시키지 않는다는 목표는 예전 `AccountNotificationListener`의 try-catch와 동일하게 유지된다.

---

## 4단계: `application/event/` Handler — `OutboxEventHandler` 구현체

이벤트 타입별 Handler는 `application/event/`에 위치하며, `outbox/OutboxEventHandler` 인터페이스를 구현한다:

```java
// outbox/OutboxEventHandler.java — 실제 코드
public interface OutboxEventHandler {
    String eventType();                       // 라우팅 키 — 도메인 이벤트 record의 simple name과 일치해야 함
    void handle(String payload) throws Exception;
}
```

```java
// account/application/event/AccountCreatedEventHandler.java — 실제 코드
@Component
@RequiredArgsConstructor
public class AccountCreatedEventHandler implements OutboxEventHandler {
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return AccountCreatedEvent.class.getSimpleName();
    }

    @Override
    public void handle(String payload) throws Exception {
        AccountCreatedEvent event = objectMapper.readValue(payload, AccountCreatedEvent.class);
        notificationService.sendEmail(event.accountId(), "AccountCreated", event.email(),
                "[Account] 계좌가 개설되었습니다",
                "계좌(" + event.accountId() + ")가 개설되었습니다. 통화: " + event.currency());
    }
}
```

나머지 5개 이벤트(`MoneyDepositedEvent`/`MoneyWithdrawnEvent`/`AccountSuspendedEvent`/`AccountReactivatedEvent`/`AccountClosedEvent`)도 각각 동일한 구조의 `MoneyDepositedEventHandler`/`MoneyWithdrawnEventHandler`/`AccountSuspendedEventHandler`/`AccountReactivatedEventHandler`/`AccountClosedEventHandler`로 존재한다 — 이메일 제목/본문/수신자 로직은 예전 `AccountNotificationListener`(삭제됨)의 해당 분기와 동일하다.

**핸들러가 Payload를 스스로 역직렬화하는 이유**: `OutboxRelay`는 `eventType`(문자열)으로 핸들러를 찾아 raw JSON payload를 그대로 넘길 뿐, 타입 정보를 갖지 않는다 — Java의 정적 타입 시스템에서 제네릭 Relay가 여러 이벤트 타입을 다루려면 이 경계에서 타입이 지워질 수밖에 없다. 각 핸들러가 `ObjectMapper.readValue(payload, XxxEvent.class)`로 자신의 이벤트 타입만 알고 역직렬화한다.

---

## 이벤트 핸들러 멱등성

`processPending()`은 테이블 전체를 재드레인하는 구조이므로, 핸들러 실행 자체는 at-least-once다(예: `processPending()` 도중 handler.handle()은 성공했지만 그 직후 `markProcessed()`/커밋 전에 크래시하면, 다음 호출에서 같은 이벤트가 다시 처리된다). 현재 Handler들은 이미 **Level 1(본질적 멱등)**에 근접한다 — 이메일 재발송이 시스템 상태를 망가뜨리지는 않는다(단, 중복 이메일 발송 자체는 발생할 수 있다). 완전한 멱등성을 원하면 Level 2(Ledger)를 적용한다. 이 저장소는 이미 `notification/infrastructure/persistence/SentEmail`(발송 이력 Entity)을 갖고 있으므로, `eventId` 컬럼만 추가하면 Ledger 역할을 겸할 수 있다:

```java
// AccountCreatedEventHandler — Level 2 적용 (제안, 아직 미도입)
public void handle(String payload) throws Exception {
    AccountCreatedEvent event = objectMapper.readValue(payload, AccountCreatedEvent.class);
    // eventId를 handle() 시그니처로 전달받아 SentEmail.existsByEventId(eventId)로 skip 판단
}
```

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## root 대비 의도적 차이 — `@Scheduled` 폴링 대신 동기 드레인

root와 대다수 프로덕션 구현은 Relay를 별도 프로세스/스레드가 주기적으로 폴링하게 한다(메시지 큐로 발행하거나, Consumer가 직접 처리). 이 저장소는 실제 메시지 큐 인프라를 두지 않고, Command Service가 자신의 저장 직후 Relay를 직접 호출하는 축약형을 택했다:

| | root/일반적 프로덕션 패턴 | 이 저장소 |
|---|---|---|
| 트리거 | `@Scheduled` 폴링(별도 스레드) | Command Service가 저장 후 동기 호출 |
| 전달 대상 | 메시지 큐(SQS 등) → Consumer | 핸들러를 직접 인프로세스 호출 |
| 지연 | 폴링 주기만큼 지연 가능 | 즉시(같은 요청 스레드 내에서 처리) |
| 결정론 | 비동기라 e2e 테스트에 대기/폴링 필요 | 동기라 e2e 테스트가 별도 대기 없이 바로 단언 가능 |
| 원자성 보장 | 동일 — Outbox 저장은 Aggregate와 같은 트랜잭션 | 동일 |

핵심 불변식(이벤트는 Aggregate와 원자적으로 커밋되고, 처리 실패 시 재시도된다)은 두 방식 모두 동일하게 성립한다 — 차이는 순수히 트리거 메커니즘과 전달 매체다. 실제 다중 인스턴스 환경에서 처리량이 늘어나면 `@Scheduled` 폴링 + 메시지 큐로 전환할 수 있으며, 그 경우의 스케줄링 안전성(다중 인스턴스 중복 실행 방지)은 [scheduling.md](scheduling.md)를 참고한다.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 방식(`domainEvents` + `pullDomainEvents()`)은 root와 일치 — 유지된다.
- **발행 메커니즘은 Outbox로 완전히 교체되었다**: `ApplicationEventPublisher` 동기 발행(제거됨) → `AccountRepositoryImpl.save()`의 Outbox 저장(같은 트랜잭션) → `OutboxRelay.processPending()`(Command Service가 저장 직후 동기 호출) → `application/event/`의 `OutboxEventHandler` 구현체.
- **Command Service에서 `eventPublisher.publishEvent()` 호출과 클래스 레벨 `@Transactional`을 모두 제거**했다 — 트랜잭션 경계는 `AccountRepositoryImpl.save()`로 이관되었다.
- **EventHandler는 아직 완전히 멱등하지 않다** — 본질적 멱등(이메일 재발송은 안전)에는 해당하지만, `SentEmail`에 `eventId`를 추가하는 Level 2 Ledger는 후속 과제다.
- `@Scheduled`/메시지 큐 기반 비동기 Relay는 도입하지 않았다 — 동기 드레인이 이 저장소의 규모(메시지 큐 인프라 부재, e2e 테스트의 결정론적 검증 요구)에 맞는 의도적 단순화다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [scheduling.md](scheduling.md) — `@Scheduled` 기반 Relay/Consumer로 전환할 경우의 참고
- [persistence.md](persistence.md) — Outbox 저장과 Aggregate 저장의 트랜잭션 원자성
- [shared-modules.md](shared-modules.md) — `outbox/` 패키지 배치 컨벤션
