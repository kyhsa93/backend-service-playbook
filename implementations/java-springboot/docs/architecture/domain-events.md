# 도메인 이벤트 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [domain-events.md](../../../../docs/architecture/domain-events.md) 참고.

## Outbox 패턴 — 실제 경로

root 원칙: **Repository가 Aggregate와 이벤트를 같은 트랜잭션에서 Outbox 테이블에 저장 → 큐 발행 → 큐 수신 시 핸들러 실행**. 이 저장소는 이 경로를 문자 그대로 구현한다 — **Command Service가 저장 직후 같은 프로세스 안에서 동기적으로 드레인하는 방식은 쓰지 않는다.**

1. **`OutboxWriter`가 Repository 구현체의 트랜잭션 안에서 outbox 테이블에 이벤트를 적재한다** (변경 없음 — 아래 1~2단계).
2. **`OutboxPoller`(`@Scheduled(fixedDelay = 1000)`)가 독립적으로 1초 주기로 outbox 테이블을 읽어 SQS로 발행한다.** Command Service는 이 클래스를 전혀 참조하지 않는다.
3. **`OutboxConsumer`(전용 단일 스레드에서 도는 long-polling 루프, `SmartLifecycle`로 시작/종료 관리)가 SQS를 수신 대기하다가 메시지를 받으면 `eventType`으로 `OutboxEventHandler` 구현체를 찾아 호출한다.**

Command Service는 `accountRepository.saveAccount(account)` 호출 뒤 곧바로 반환한다 — Outbox → SQS 발행/수신은 언제 일어나는지 Command Service가 전혀 알지 못한다(최대 1초 뒤 발행 + SQS long-poll 지연). `harness/src/rules/OutboxDrainOrder.java`가 Command Service(`application/command/`)에서 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` 심볼 참조나 `processPending()`/`poll()`/`drainOnce()` 호출을 찾으면 실패시킨다 — 예전에는 반대로 "호출해야 한다"는 규칙이었지만, 동기 드레인을 전면 제거하며 뒤집혔다.

```java
// application/command/CreateAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
public class CreateAccountService {

    private final AccountRepository accountRepository;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.saveAccount(account);   // Account + Outbox 행을 한 트랜잭션으로 커밋
        return new CreateAccountResult(/* ... */);
        // 여기서 끝난다 — Outbox → SQS 발행과 EventHandler 실행은 OutboxPoller/OutboxConsumer가
        // 독립적으로 처리한다.
    }
}
```

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

`@Transient`가 이 필드를 JPA 컬럼 매핑에서 제외한다 — 이벤트는 영속 상태가 아니라 "아직 전달되지 않은 사실의 목록"이다. 이 수집 패턴은 Outbox 도입 전후, 그리고 동기 드레인 → 비동기 전환 전후로도 전혀 바뀌지 않았다.

---

## 2단계: Repository가 Aggregate + Outbox를 한 트랜잭션으로 저장 (변경 없음)

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
    private boolean processed = false;          // 의미가 바뀌었다 — 아래 3단계 참고

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
    public void saveAccount(Account account) {
        jpaRepository.save(account);
        List<Transaction> pending = account.pullPendingTransactions();
        if (!pending.isEmpty()) {
            transactionJpaRepository.saveAll(pending);
        }
        outboxWriter.saveAll(account.pullDomainEvents());
    }
}
```

**Command Service는 `ApplicationEventPublisher`/`@EventListener`에 의존하지 않는다.** 9개 Command Service(Account 6개 + Payment 3개) 모두 `ApplicationEventPublisher` 의존성이 없고, 클래스 레벨 `@Transactional`도 붙지 않으며, **이제는 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` 필드도 갖지 않는다** — 유일한 물리 트랜잭션 경계는 `AccountRepositoryImpl.saveAccount()`(혹은 각 BC의 동등한 Repository 구현체)다.

---

## 3단계: `OutboxPoller` — Outbox → SQS 발행 (신규, `@Scheduled`)

Outbox 테이블에서 `processed=false`인 행을 읽어 SQS로 발행하고, **발행에 성공한 즉시** `processed=true`로 표시한다. `processed`의 의미가 "핸들러가 처리를 끝냈다"에서 "SQS로 전달을 끝냈다"로 바뀌었다 — 이후의 재시도/at-least-once 보장은 outbox 테이블이 아니라 SQS의 visibility timeout + DLQ가 담당한다.

```java
// outbox/OutboxPoller.java — 실제 코드(발췌)
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxEventJpaRepository outboxJpaRepository;
    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxJpaRepository.findByProcessedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                sqsClient.sendMessage(SendMessageRequest.builder()
                        .queueUrl(sqsProperties.domainEventQueueUrl())
                        .messageBody(event.getPayload())
                        .messageAttributes(Map.of("eventType", MessageAttributeValue.builder()
                                .dataType("String").stringValue(event.getEventType()).build()))
                        .build());
                event.markProcessed();
                outboxJpaRepository.save(event);
            } catch (Exception e) {
                log.error("SQS 발행 실패", kv("event_type", event.getEventType()), kv("event_id", event.getEventId()), e);
                // processed=false로 남아 다음 tick에서 재시도된다.
            }
        }
    }
}
```

**왜 `@Transactional`이 필요한가**: `OutboxEvent.payload`는 `@Lob` 컬럼이다. `poll()` 자체가 트랜잭션 경계가 아니면, `findByProcessedFalseOrderByCreatedAtAsc()`가 자신만의 짧은 트랜잭션으로 실행되고 끝나버려서, 그 다음 `for` 루프에서 `event.getPayload()`를 늦게 읽으려 할 때 세션/커넥션이 이미 반환된 상태라 `Unable to access lob stream` 예외가 발생한다(실제로 이 실수를 했다가 E2E 테스트에서 이벤트가 전혀 SQS로 발행되지 않는 회귀로 잡혔다). 옛 `OutboxRelay.processPending()`도 동일한 이유로 `@Transactional`이 붙어 있었다 — 동기 드레인을 비동기로 바꾸면서도 "LOB 조회와 반복은 같은 트랜잭션 안에서" 필요성은 그대로 남는다.

**겹쳐 실행 방지**: Spring의 `@Scheduled(fixedDelay = ...)`는 "이전 실행이 끝난 뒤부터" 다음 실행까지의 간격을 보장한다 — 기본 스케줄러가 단일 스레드이므로 이전 tick의 드레인이 아직 끝나지 않았으면 다음 tick이 겹쳐 실행되지 않는다. nestjs의 `OutboxPoller`가 `isPolling` 플래그를 직접 관리하는 것과 동일한 효과를 Spring 프레임워크가 대신 보장해준다 — Java 구현에는 별도 플래그가 필요 없다.

**`@EnableScheduling` 활성화**: `@Scheduled`가 동작하려면 `AccountServiceApplication`에 `@EnableScheduling`이 있어야 한다.

```java
// AccountServiceApplication.java — 실제 코드(발췌)
@SpringBootApplication
@EnableScheduling   // OutboxPoller의 @Scheduled(fixedDelay = 1000)을 활성화한다.
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class, JwtProperties.class, SqsProperties.class})
public class AccountServiceApplication { ... }
```

---

## 4단계: `OutboxConsumer` — SQS → `OutboxEventHandler` 수신 (신규, long polling)

SQS를 long polling(`ReceiveMessageRequest.waitTimeSeconds(5)`)으로 수신 대기하다가 메시지를 받으면 `eventType`(`MessageAttributes`)으로 Spring이 자동 수집한 `List<OutboxEventHandler>`에서 핸들러를 찾아 호출한다. **`OutboxRelay`가 하던 "클래스패스 전체에서 `OutboxEventHandler` 구현체 자동 수집" 배선은 그대로 재사용된다** — 주입 대상만 `OutboxRelay`에서 `OutboxConsumer`로 바뀌었을 뿐, 각 도메인의 `OutboxEventHandler` 구현체 자체는 손댈 필요가 없다.

```java
// outbox/OutboxConsumer.java — 실제 코드(발췌)
@Component
public class OutboxConsumer implements SmartLifecycle {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final Map<String, OutboxEventHandler> handlers;   // eventType() -> handler, 생성자에서 한 번 구성
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "outbox-consumer"));
    private volatile boolean running = false;

    public OutboxConsumer(SqsClient sqsClient, SqsProperties sqsProperties, List<OutboxEventHandler> eventHandlers) {
        this.sqsClient = sqsClient;
        this.sqsProperties = sqsProperties;
        this.handlers = eventHandlers.stream()
                .collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
    }

    @Override
    public void start() {              // ApplicationContext refresh 완료 시 자동 호출
        running = true;
        executor.submit(this::pollLoop);   // 즉시 반환 — 부트스트랩을 막지 않는다
    }

    @Override
    public void stop() {                // Graceful Shutdown 시 자동 호출
        running = false;
        executor.shutdown();
        // awaitTermination(...) — 진행 중인 ReceiveMessage 호출이 끝날 때까지 기다린다
    }

    private void pollLoop() {
        String queueUrl = sqsProperties.domainEventQueueUrl();
        while (running) {
            ReceiveMessageResponse response = sqsClient.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl).maxNumberOfMessages(10)
                    .messageAttributeNames("eventType").waitTimeSeconds(5).build());
            for (Message message : response.messages()) {
                handleMessage(queueUrl, message);
            }
        }
    }

    private void handleMessage(String queueUrl, Message message) {
        String eventType = /* message.messageAttributes().get("eventType").stringValue() */ null;
        try {
            OutboxEventHandler handler = handlers.get(eventType);
            if (handler == null) throw new IllegalStateException("등록된 핸들러가 없습니다: " + eventType);
            handler.handle(message.body());
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
        } catch (Exception e) {
            log.error("이벤트 처리 실패: eventType={}", eventType, e);
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
        }
    }
}
```

### 백그라운드 루프 설계 — 왜 `@Scheduled`가 아니라 `SmartLifecycle` + 전용 스레드인가

`@Scheduled(fixedDelay = ...)`는 "고정 간격으로 짧게 실행되고 끝나는 작업"(예: `OutboxPoller`)에 적합하지만, `OutboxConsumer`의 루프는 `waitTimeSeconds(5)`로 최대 5초씩 블로킹하는 긴 수신 대기를 계속 반복해야 한다. `@Scheduled`의 기본 스레드 풀에 이런 블로킹 작업을 물려두면 다른 `@Scheduled` 작업(`OutboxPoller` 등)의 실행이 지연될 위험이 있다.

그래서 전용 단일 스레드 `ExecutorService`에 루프를 제출하고, `SmartLifecycle`로 시작/종료를 관리한다:

- **`start()`**: `ApplicationContext` refresh(부트스트랩) 완료 시 Spring이 자동으로 호출한다. `executor.submit(this::pollLoop)`은 즉시 반환하므로 메인 스레드(부트스트랩)를 막지 않는다 — nestjs의 `OnModuleInit.onModuleInit()`이 `void this.pollLoop()`로 fire-and-forget하는 것과 동일한 효과다.
- **`stop()`**: Graceful Shutdown(`server.shutdown: graceful`) 시 `ApplicationContext` 종료 과정에서 Spring이 자동으로 호출한다. `running = false` 이후 `executor.shutdown()` + `awaitTermination(...)`으로, 진행 중인 `ReceiveMessage` 호출(최대 `waitTimeSeconds`)이 끝날 때까지 기다린 뒤 graceful하게 종료한다 — nestjs의 `OnModuleDestroy.onModuleDestroy()`와 동일한 원칙([graceful-shutdown.md](graceful-shutdown.md) 참고).

`@PostConstruct`/`ApplicationListener<ApplicationReadyEvent>`도 "컨텍스트 준비 완료 후 시작"은 가능하지만, `SmartLifecycle`만이 **종료 시점의 훅(`stop()`)** 도 프레임워크가 대신 호출해준다 — 별도로 `@PreDestroy`를 얹어 `running` 플래그를 끄는 코드를 추가로 작성할 필요가 없다.

---

## `OutboxEventHandler` — 변경 없음

이벤트 타입별 Handler는 `application/event/`에 위치하며, `outbox/OutboxEventHandler` 인터페이스를 구현한다. 이 인터페이스와 각 도메인의 구현체(`AccountCreatedEventHandler` 등)는 동기 드레인 → 비동기 전환에도 **전혀 바뀌지 않았다** — 라우팅 주체만 `OutboxRelay`에서 `OutboxConsumer`로 바뀌었다.

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

나머지 이벤트(`MoneyDepositedEvent`/`MoneyWithdrawnEvent`/`AccountSuspendedEvent`/`AccountReactivatedEvent`/`AccountClosedEvent`)도 각각 동일한 구조의 Handler로 존재하고, Card/Payment BC의 Integration Event 수신부(`AccountSuspendedIntegrationEventHandler` 등)도 같은 인터페이스를 구현한다 — `OutboxConsumer`가 Domain Event Handler든 Integration Event 수신부든 구분하지 않고 동일하게 `eventType()`으로 라우팅한다.

**핸들러가 Payload를 스스로 역직렬화하는 이유**: `OutboxConsumer`는 `eventType`(문자열)으로 핸들러를 찾아 raw JSON payload를 그대로 넘길 뿐, 타입 정보를 갖지 않는다 — Java의 정적 타입 시스템에서 제네릭 Consumer가 여러 이벤트 타입을 다루려면 이 경계에서 타입이 지워질 수밖에 없다. 각 핸들러가 `ObjectMapper.readValue(payload, XxxEvent.class)`로 자신의 이벤트 타입만 알고 역직렬화한다.

---

## SQS 클라이언트 — 실제 코드

기존 SES/Secrets Manager 클라이언트 구성(`AwsProperties`의 `AWS_ENDPOINT_URL` 분기, 정적 test 자격증명)을 그대로 따른다.

```java
// outbox/SqsConfig.java — 실제 코드(발췌)
@Configuration
@RequiredArgsConstructor
public class SqsConfig {
    private final AwsProperties awsProperties;

    @Bean
    public SqsClient sqsClient() {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(awsProperties.accessKeyId(), awsProperties.secretAccessKey())));
        if (awsProperties.endpointUrl() != null && !awsProperties.endpointUrl().isBlank()) {
            builder.endpointOverride(URI.create(awsProperties.endpointUrl()));
        }
        return builder.build();
    }
}
```

큐 URL은 `SqsProperties.domainEventQueueUrl()`이 `SQS_DOMAIN_EVENT_QUEUE_URL` 환경 변수(`sqs.domain-event-queue-url` 프로퍼티)에서 읽는다 — `@NotBlank` + `@Validated`로 fail-fast([config.md](config.md) 참고).

```java
// config/SqsProperties.java — 실제 코드
@ConfigurationProperties(prefix = "sqs")
@Validated
public record SqsProperties(@NotBlank String domainEventQueueUrl) {}
```

---

## LocalStack + Docker Compose — 실제 코드

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
# .env.example — SQS 큐 URL 추가
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

DLQ + `maxReceiveCount=3`은 [scheduling.md — DLQ 모니터링](../../../../docs/architecture/scheduling.md#dlq-모니터링)이 요구하는 컨벤션을 그대로 따른다 — nestjs의 `localstack/init-sqs.sh`와 동일한 큐 이름·구성으로 언어 간 일관성을 유지한다.

---

## E2E 테스트 — 비동기이므로 폴링-타임아웃으로 검증한다

`CardControllerE2ETest`/`PaymentControllerE2ETest`/`NotificationE2ETest`는 Testcontainers `LocalStackContainer`(SQS 서비스 포함)를 띄우고, 테스트 시작 시 SDK로 직접 `domain-events`/`domain-events-dlq` 큐를 만든다(`support/SqsTestQueue.java` — `localstack/init-sqs.sh`와 동일한 구성을 SDK 호출로 재현; Testcontainers `LocalStackContainer`는 로컬 init 스크립트를 마운트하지 않는다).

이제 카드 상태 전환(계좌 정지/종료 → Card BC 반응), 계좌 잔액 변경(결제 완료/취소/환불 → Account BC 반응), 이메일 발송(계좌 생성/입금 → SES) 모두 **HTTP 응답을 받은 시점에 아직 완료되지 않았을 수 있다** — `OutboxPoller`가 다음 tick(최대 1초)에 SQS로 발행하고, `OutboxConsumer`가 수신해야 실제로 반영된다. 그래서 [Awaitility](https://github.com/awaitility/awaitility)로 폴링-타임아웃 헬퍼를 만들어 즉시 assert를 대체한다:

```java
// CardControllerE2ETest.java — 실제 코드(발췌)
private void waitForCardStatus(String cardId, String expected, String ownerId) {
    await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() ->
                    assertThat(get("/cards/" + cardId, ownerId).getBody().get("status")).isEqualTo(expected));
}
```

`atMost(30초)`는 실제 SQS+LocalStack 왕복 지연(폴링 주기 1초 + long poll 대기 최대 5초 + 컨테이너 오버헤드)에 여유를 둔 값이다. `untilAsserted`는 타임아웃 시 마지막 `AssertionError`(실제 vs 기대값)를 그대로 노출해줘서 디버깅에 유리하다. `AccountControllerE2ETest`는 계좌 자체의 상태(정지/재개/종료)를 검증하는데, 이는 Command Service가 저장 시점에 동기적으로 바꾸는 값이라 Outbox를 거치지 않으므로 폴링이 필요 없다 — 폴링이 필요한 것은 **다른 BC/Technical Service가 이벤트에 반응해 바꾸는 값**뿐이다.

---

## 이벤트 핸들러 멱등성

SQS는 at-least-once 전달을 보장한다. 같은 메시지가 **중복 수신**될 수 있으므로 모든 `OutboxEventHandler`는 **멱등(idempotent)** 하게 구현해야 한다 — 이 요구사항은 동기 드레인 시절(테이블 재드레인에 의한 at-least-once)에도 이미 성립했고, SQS 전환으로 전달 매체가 바뀌었을 뿐 원칙은 동일하다.

현재 Handler들은 이미 **Level 1(본질적 멱등)**에 근접한다 — 이메일 재발송이 시스템 상태를 망가뜨리지는 않는다(단, 중복 이메일 발송 자체는 발생할 수 있다). 완전한 멱등성을 원하면 Level 2(Ledger)를 적용한다. 이 저장소는 이미 `account/infrastructure/notification/persistence/SentEmail`(발송 이력 Entity)을 갖고 있으므로, `eventId` 컬럼만 추가하면 Ledger 역할을 겸할 수 있다. `DepositByPaymentService`/`WithdrawByPaymentService`는 `referenceId` 기준 Level 2 Ledger(`hasTransactionWithReference`)를 실제로 쓴다.

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## harness 검증

`harness/src/rules/OutboxDrainOrder.java`(rule: `outbox-drain-order`)가 Command Service(`application/command/`)에서 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` 심볼 참조나 `processPending()`/`poll()`/`drainOnce()` 호출을 찾으면 실패시킨다. `harness/src/rules/SharedInfra.java`(rule: `shared-infra`)는 `OutboxWriter` 참조가 있으면 `outbox/`에 `OutboxWriter.java`/`OutboxPoller.java`/`OutboxConsumer.java`가 모두 존재하는지 확인한다 — 두 규칙 모두 fixture(`harness/test/testdata/`)로 회귀 검증된다.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 방식(`domainEvents` + `pullDomainEvents()`)은 root와 일치 — 유지된다.
- **발행/수신 메커니즘은 SQS 경유 비동기로 완전히 교체되었다**: `AccountRepositoryImpl.saveAccount()`의 Outbox 저장(같은 트랜잭션, 변경 없음) → `OutboxPoller`(`@Scheduled(fixedDelay=1000)`, 신규)가 SQS로 발행 → `OutboxConsumer`(long polling, `SmartLifecycle`, 신규)가 수신해 `application/event/`의 `OutboxEventHandler` 구현체 호출.
- **Command Service는 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 전혀 참조하지 않는다** — 저장 직후 곧바로 반환한다. `harness`의 `outbox-drain-order` 규칙이 회귀(누군가 다시 동기 호출을 추가하는 것)를 방지한다.
- **`OutboxEventHandler` 인터페이스와 각 도메인 구현체는 바뀌지 않았다** — Spring의 `List<OutboxEventHandler>` 자동 수집을 `OutboxConsumer`가 그대로 재사용한다.
- **EventHandler는 아직 완전히 멱등하지 않다** — 본질적 멱등(이메일 재발송은 안전)에는 해당하지만, `SentEmail`에 `eventId`를 추가하는 Level 2 Ledger는 후속 과제다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [scheduling.md](scheduling.md) — `@Scheduled`/DLQ/멱등성 컨벤션(이 문서가 실제로 구현한 대상)
- [persistence.md](persistence.md) — Outbox 저장과 Aggregate 저장의 트랜잭션 원자성
- [graceful-shutdown.md](graceful-shutdown.md) — `OutboxConsumer`의 `SmartLifecycle.stop()` graceful 종료
- [local-dev.md](local-dev.md) — LocalStack SQS 구성
- [shared-modules.md](shared-modules.md) — `outbox/` 패키지 배치 컨벤션
