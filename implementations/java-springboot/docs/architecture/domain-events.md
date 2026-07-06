# 도메인 이벤트 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md) 참고.

## 알려진 gap — 현재는 Outbox 없이 동기 in-process 발행

root 원칙: Domain Event는 in-process 이벤트 버스를 사용하지 않는다. **Repository가 Aggregate와 이벤트를 같은 트랜잭션에서 Outbox 테이블에 저장 → Relay가 폴링하여 메시지 큐로 전송 → Consumer가 수신해 처리**하는 경로를 따른다.

이 저장소의 `examples/`는 이 경로를 전혀 구현하지 않는다:

```java
// application/command/CreateAccountService.java — 현재 코드
public CreateAccountResult create(CreateAccountCommand command) {
    Account account = Account.create(command.requesterId(), command.email(), command.currency());
    accountRepository.save(account);
    account.pullDomainEvents().forEach(eventPublisher::publishEvent);   // ← 동기 in-process 발행
    return new CreateAccountResult(/* ... */);
}
```

```java
// application/event/AccountNotificationListener.java — 현재 코드
@Component
@RequiredArgsConstructor
public class AccountNotificationListener {
    private final NotificationService notificationService;

    @EventListener
    public void on(AccountCreatedEvent event) {
        send(event.accountId(), "AccountCreated", event.email(), /* ... */);
    }

    private void send(String accountId, String eventType, String recipient, String subject, String body) {
        try {
            notificationService.sendEmail(accountId, eventType, recipient, subject, body);
        } catch (Exception e) {
            log.error("알림 이메일 발송 실패: accountId={}, eventType={}", accountId, eventType, e);
        }
    }
}
```

`ApplicationEventPublisher.publishEvent()`는 기본적으로 **같은 스레드, 같은 트랜잭션 안에서 동기적으로** 리스너를 호출한다. Outbox 테이블, at-least-once 전달, 멱등성 전략이 전혀 없다 — 리스너 내부의 `try-catch`가 "알림 실패가 원본 계좌 커맨드를 실패시키지 않도록" 막아주긴 하지만, **애플리케이션이 이벤트 발행 직후, 커밋 이전에 크래시하면 알림은 영구히 유실된다**(이벤트가 어디에도 영속화되지 않았으므로 재시도할 방법이 없다). 이는 의도된 단순화이지 실수는 아니지만, root 패턴과의 괴리는 명확하다. 아래는 root가 요구하는 올바른 Outbox 기반 패턴이며 `examples/`에는 아직 반영되어 있지 않다.

---

## 1단계: Aggregate에서 이벤트 수집 (현재 코드 — 이 부분은 root와 일치)

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

`@Transient`가 이 필드를 JPA 컬럼 매핑에서 제외한다 — 이벤트는 영속 상태가 아니라 "아직 전달되지 않은 사실의 목록"이다. **이 수집 패턴 자체는 유지한다.** 바뀌어야 하는 것은 다음 단계(발행 메커니즘)다.

---

## 2단계: Repository에서 Aggregate + Outbox를 한 트랜잭션으로 저장 (올바른 패턴)

```java
// infrastructure/outbox/OutboxEvent.java — 신규 Entity
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private boolean processed = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected OutboxEvent() {}

    public static OutboxEvent from(Object domainEvent, ObjectMapper objectMapper) {
        try {
            OutboxEvent outboxEvent = new OutboxEvent();
            outboxEvent.eventId = UUID.randomUUID().toString().replace("-", "");
            outboxEvent.eventType = domainEvent.getClass().getSimpleName();
            outboxEvent.payload = objectMapper.writeValueAsString(domainEvent);
            outboxEvent.createdAt = LocalDateTime.now();
            return outboxEvent;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("이벤트 직렬화 실패", e);
        }
    }

    public void markProcessed() { this.processed = true; }
}
```

```java
// infrastructure/persistence/AccountRepositoryImpl.java — 올바른 save() (제안)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;
    private final OutboxEventJpaRepository outboxJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional   // Account 저장 + Outbox 저장이 하나의 물리 트랜잭션
    public void save(Account account) {
        jpaRepository.save(account);
        List<Object> events = account.pullDomainEvents();
        if (!events.isEmpty()) {
            List<OutboxEvent> outboxEvents = events.stream()
                    .map(e -> OutboxEvent.from(e, objectMapper))
                    .toList();
            outboxJpaRepository.saveAll(outboxEvents);
        }
    }
}
```

**Command Service는 더 이상 `eventPublisher.publishEvent()`를 호출하지 않는다.** `CreateAccountService.create()`에서 `account.pullDomainEvents().forEach(eventPublisher::publishEvent)` 줄을 제거한다 — Outbox 저장은 `Repository.save()` 내부 책임이며, 이 한 번의 호출로 Aggregate 상태와 이벤트가 원자적으로 커밋되거나 함께 롤백된다.

---

## 3단계: OutboxRelay — `@Scheduled` 폴링 후 메시지 큐 발행

```java
// infrastructure/outbox/OutboxRelay.java
@Component
@RequiredArgsConstructor
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final SqsClient sqsClient;

    @Value("${app.outbox.queue-url}")
    private String queueUrl;

    @Scheduled(fixedDelay = 1000)   // 1초 간격 폴링 — Scheduler는 Infrastructure 레이어, scheduling.md 참고
    public void relay() {
        List<OutboxEvent> pending = outboxJpaRepository.findTop100ByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .messageBody(event.getPayload())
                        .messageGroupId(event.getEventType())
                        .messageDeduplicationId(event.getEventId())
                        .build());
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("Outbox relay 실패: eventId={}", event.getEventId(), e);
                // processed가 갱신되지 않으므로 다음 폴링에서 재시도된다
            }
        }
    }
}
```

큐 발행 실패 시 `processed`가 갱신되지 않아 다음 폴링에서 재시도된다 — at-least-once 전달이 여기서부터 시작된다.

---

## 4단계: EventConsumer → `application/event/` Handler

메시지 큐에서 수신한 이벤트는 `application/event/`의 Handler가 처리한다. 현재 `AccountNotificationListener`의 역할과 동일하지만, 호출 경로가 동기 `@EventListener`가 아니라 **비동기 메시지 큐 Consumer**로 바뀐다.

```java
// infrastructure/outbox/EventConsumer.java — SQS 폴링 → eventType으로 라우팅
@Component
@RequiredArgsConstructor
public class EventConsumer {
    private final SqsClient sqsClient;
    private final Map<String, EventHandler<?>> handlerRegistry;   // eventType -> Handler

    @Value("${app.outbox.queue-url}")
    private String queueUrl;

    @Scheduled(fixedDelay = 500)
    public void poll() {
        List<Message> messages = sqsClient.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)).messages();
        for (Message message : messages) {
            String eventType = message.messageAttributes().get("eventType").stringValue();
            EventHandler<?> handler = handlerRegistry.get(eventType);
            if (handler != null) {
                handler.handle(message.body());
            }
            sqsClient.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
    }
}
```

`AccountNotificationListener`는 `@EventListener` 대신 `EventHandler` 인터페이스를 구현하도록 바뀌지만, 내부 로직(이벤트별 이메일 제목/본문 구성 → `NotificationService.sendEmail()` 호출)은 그대로 재사용된다.

---

## 이벤트 핸들러 멱등성

메시지 큐는 at-least-once 전달이다. `AccountNotificationListener`의 핸들러들은 이미 **Level 1(본질적 멱등)**에 근접한다 — 이메일 재발송이 시스템 상태를 망가뜨리지는 않는다(단, 중복 이메일 발송 자체는 발생한다). 완전한 멱등성을 원하면 Level 2(Ledger)를 적용한다. 이 저장소는 이미 `notification/infrastructure/persistence/SentEmail`(발송 이력 Entity)을 갖고 있으므로, `eventId` 컬럼만 추가하면 Ledger 역할을 겸할 수 있다:

```java
// AccountNotificationListener — Level 2 적용 (제안)
public void handle(String eventId, MoneyDepositedEvent event) {
    if (sentEmailRepository.existsByEventId(eventId)) return;   // 이미 처리됨 — skip
    notificationService.sendEmail(/* ... */);
}
```

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 현재 방식(`domainEvents` + `pullDomainEvents()`)은 root와 일치 — 유지한다.
- **발행 메커니즘은 Outbox로 교체해야 한다**: `ApplicationEventPublisher` 동기 발행 → `Repository.save()`의 Outbox 저장 → `OutboxRelay`(`@Scheduled`) → 메시지 큐 → `EventConsumer`.
- **Command Service에서 `eventPublisher.publishEvent()` 호출을 제거**하고 Repository 내부로 이관한다.
- **EventHandler는 멱등하게 구현한다** — `SentEmail`에 `eventId`를 추가해 Ledger로 활용할 수 있다.
- 이 저장소는 현재 이 리팩터링 이전 상태다 — `examples/` 코드 변경은 이번 문서화 패스의 범위 밖이며 후속 이슈로 트래킹한다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [scheduling.md](scheduling.md) — `@Scheduled` 기반 Relay/Consumer 폴링
- [persistence.md](persistence.md) — Outbox 저장과 Aggregate 저장의 트랜잭션 원자성
