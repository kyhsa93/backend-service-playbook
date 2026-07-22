# Domain Events (Spring Boot)

> For the framework-agnostic principles, see the root [domain-events.md](../../../../docs/architecture/domain-events.md).

## The Outbox pattern — the actual path

Root principle: **the Repository saves the Aggregate and the event to the Outbox table in the same transaction → publish to a queue → run the handler on queue receipt**. This repository implements this path literally — **it does not synchronously drain within the same process, right after saving, from the Command Service.**

1. **`OutboxWriter` writes the event to the outbox table inside the Repository implementation's transaction** (steps 1–2 below).
2. **`OutboxPoller` (`@Scheduled(fixedDelay = 1000)`) independently reads the outbox table every 1 second and publishes to SQS.** The Command Service never references this class at all.
3. **`OutboxConsumer` (a long-polling loop running on a dedicated single thread, with start/stop managed via `SmartLifecycle`) waits to receive from SQS, and on receiving a message, looks up the `OutboxEventHandler` implementation by `eventType` and invokes it.**

The Command Service returns immediately after calling `accountRepository.saveAccount(account)` — it has no knowledge of when the Outbox → SQS publish/receive actually happens (up to 1 second delay for publishing, plus SQS long-poll latency). `harness/src/rules/OutboxDrainOrder.java` fails the build if it finds a reference to the `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` symbols, or a call to `processPending()`/`poll()`/`drainOnce()`, inside a Command Service (`application/command/`) — a Command Service must return immediately after saving, and must never synchronously invoke the Outbox drain.

```java
// application/command/CreateAccountService.java — actual code
@Service
@RequiredArgsConstructor
public class CreateAccountService {

    private final AccountRepository accountRepository;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.saveAccount(account);   // commits the Account row + Outbox row in one transaction
        return new CreateAccountResult(/* ... */);
        // Ends here — the Outbox → SQS publish and the EventHandler execution are handled
        // independently by OutboxPoller/OutboxConsumer.
    }
}
```

---

## Step 1: collecting events from the Aggregate

```java
// domain/Account.java — actual code
@Transient
private final List<Object> domainEvents = new ArrayList<>();

public Transaction deposit(long amount) {
    // ... invariant validation ...
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

`@Transient` excludes this field from JPA column mapping — an event isn't persistent state, it's "a list of facts not yet delivered."

---

## Step 2: the Repository saves the Aggregate + Outbox in one transaction

```java
// outbox/OutboxEvent.java — actual code
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @Column(length = 32, nullable = false, updatable = false)
    private String eventId;                    // UUID.randomUUID() with hyphens removed, 32-character hex

    @Column(nullable = false, updatable = false)
    private String eventType;                  // the simple name of the domain event record, e.g. "AccountCreatedEvent"

    @Lob
    @Column(nullable = false, updatable = false)
    private String payload;                     // JSON serialized via ObjectMapper

    @Column(nullable = false)
    private boolean processed = false;          // means "delivery to SQS has finished" — see step 3 below

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ... create() static factory, markProcessed(), getters ...
}
```

```java
// outbox/OutboxWriter.java — actual code
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
            throw new IllegalStateException("Failed to serialize event: " + event.getClass().getSimpleName(), e);
        }
    }
}
```

```java
// account/infrastructure/persistence/AccountRepositoryImpl.java — actual code
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final OutboxWriter outboxWriter;
    private final EntityManager em;

    @Override
    @Transactional   // saving Account + Transaction + Outbox is one physical transaction
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

**The Command Service never depends on `ApplicationEventPublisher`/`@EventListener`.** No Command Service in any BC — Account, Card, or Payment — has an `ApplicationEventPublisher` dependency, carries a class-level `@Transactional`, or holds an `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` field — the only physical transaction boundary is `AccountRepositoryImpl.saveAccount()` (or the equivalent Repository implementation in each BC).

---

## Step 3: `OutboxPoller` — Outbox → SQS publish (`@Scheduled`)

It reads rows with `processed=false` from the Outbox table, publishes them to SQS, and marks them `processed=true` **the instant the publish succeeds**. `processed` means "delivery to SQS has finished" — retries/at-least-once guarantees beyond that point are handled not by the outbox table but by SQS's visibility timeout + DLQ.

```java
// outbox/OutboxPoller.java — actual code (excerpt)
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
                log.error("SQS publish failed", kv("event_type", event.getEventType()), kv("event_id", event.getEventId()), e);
                // stays processed=false and is retried on the next tick.
            }
        }
    }
}
```

**Why `@Transactional` is required**: `OutboxEvent.payload` is an `@Lob` column. If `poll()` itself were not a transaction boundary, `findByProcessedFalseOrderByCreatedAtAsc()` would run and complete within its own short-lived transaction, so reading `event.getPayload()` later in the `for` loop would hit an already-released session/connection and throw `Unable to access lob stream`. Keeping the LOB read and the loop inside the same transaction is exactly what `@Transactional` here guarantees.

**Preventing overlapping runs**: Spring's `@Scheduled(fixedDelay = ...)` guarantees the interval to the next run is measured "from when the previous run finished" — since the default scheduler is single-threaded, the next tick never overlaps with a drain from the previous tick that hasn't finished yet. This gives the same effect as nestjs's `OutboxPoller` explicitly managing an `isPolling` flag, except the Spring framework guarantees it instead — the Java implementation needs no separate flag.

**Enabling `@EnableScheduling`**: for `@Scheduled` to work, `AccountServiceApplication` must carry `@EnableScheduling`.

```java
// AccountServiceApplication.java — actual code (excerpt)
@SpringBootApplication
@EnableScheduling   // enables OutboxPoller's @Scheduled(fixedDelay = 1000)
@EnableConfigurationProperties({AwsProperties.class, SesProperties.class, JwtProperties.class, SqsProperties.class})
public class AccountServiceApplication { ... }
```

---

## Step 4: `OutboxConsumer` — SQS → `OutboxEventHandler` receipt (long polling)

It waits on SQS via long polling (`ReceiveMessageRequest.waitTimeSeconds(5)`), and when a message arrives, looks up the matching handler by `eventType` (`MessageAttributes`) from the `List<OutboxEventHandler>` that Spring automatically collects, then invokes it. **The wiring that automatically collects every `OutboxEventHandler` implementation across the classpath is shared** — each domain's `OutboxEventHandler` implementation only needs to satisfy the interface; it's entirely independent of which component performs the collection and routing.

```java
// outbox/OutboxConsumer.java — actual code (excerpt)
@Component
public class OutboxConsumer implements SmartLifecycle {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProperties;
    private final Map<String, OutboxEventHandler> handlers;   // eventType() -> handler, built once in the constructor
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
    public void start() {              // called automatically when ApplicationContext refresh completes
        running = true;
        executor.submit(this::pollLoop);   // returns immediately — doesn't block bootstrap
    }

    @Override
    public void stop() {                // called automatically during graceful shutdown
        running = false;
        executor.shutdown();
        // awaitTermination(...) — waits for any in-flight ReceiveMessage call to finish
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
            if (handler == null) throw new IllegalStateException("No registered handler for: " + eventType);
            handler.handle(message.body());
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                    .queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
        } catch (Exception e) {
            log.error("Event handling failed: eventType={}", eventType, e);
            // not deleted — re-received and retried after the visibility timeout.
        }
    }
}
```

### Background-loop design — why `SmartLifecycle` + a dedicated thread instead of `@Scheduled`

`@Scheduled(fixedDelay = ...)` fits "short tasks that run at a fixed interval and finish" (e.g. `OutboxPoller`), but `OutboxConsumer`'s loop must keep repeating a long receive-wait that blocks for up to 5 seconds via `waitTimeSeconds(5)`. Loading this kind of blocking work onto `@Scheduled`'s default thread pool risks delaying the execution of other `@Scheduled` tasks (like `OutboxPoller`).

So the loop is submitted to a dedicated single-thread `ExecutorService`, with start/stop managed by `SmartLifecycle`:

- **`start()`**: Spring calls this automatically when `ApplicationContext` refresh (bootstrap) completes. `executor.submit(this::pollLoop)` returns immediately, so it never blocks the main thread (bootstrap) — the same effect as nestjs's `OnModuleInit.onModuleInit()` firing `void this.pollLoop()` in a fire-and-forget manner.
- **`stop()`**: Spring calls this automatically during `ApplicationContext` shutdown as part of graceful shutdown (`server.shutdown: graceful`). After `running = false`, `executor.shutdown()` + `awaitTermination(...)` wait for any in-flight `ReceiveMessage` call (up to `waitTimeSeconds`) to finish before shutting down gracefully — the same principle as nestjs's `OnModuleDestroy.onModuleDestroy()` (see [graceful-shutdown.md](graceful-shutdown.md)).

`@PostConstruct`/`ApplicationListener<ApplicationReadyEvent>` can also handle "start once the context is ready," but only `SmartLifecycle` has the framework call a **shutdown-time hook (`stop()`)** for you as well — there's no need to additionally write a `@PreDestroy` method that flips the `running` flag off.

---

## `OutboxEventHandler`

Handlers for each event type live in `application/event/` and implement the `outbox/OutboxEventHandler` interface. This interface and each domain's implementation (`AccountCreatedEventHandler`, etc.) only need to satisfy the same signature (`eventType()`/`handle(payload)`), independent of how `OutboxConsumer` decides routing by event type.

```java
// outbox/OutboxEventHandler.java — actual code
public interface OutboxEventHandler {
    String eventType();                       // the routing key — must match the domain event record's simple name
    void handle(String payload) throws Exception;
}
```

```java
// account/application/event/AccountCreatedEventHandler.java — actual code
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
                "[Account] Your account has been opened",
                "Account (" + event.accountId() + ") has been opened. Currency: " + event.currency());
    }
}
```

The remaining events (`MoneyDepositedEvent`/`MoneyWithdrawnEvent`/`AccountSuspendedEvent`/`AccountReactivatedEvent`/`AccountClosedEvent`) each have a Handler of the same structure, and the Integration Event receivers in the Card/Payment BCs (`AccountSuspendedIntegrationEventHandler`, etc.) implement the same interface — `OutboxConsumer` routes to all of them uniformly by `eventType()`, without distinguishing Domain Event Handlers from Integration Event receivers.

**Why each handler deserializes its own payload**: `OutboxConsumer` looks up a handler by `eventType` (a string) and simply passes it the raw JSON payload — it carries no type information. In Java's static type system, a generic Consumer handling multiple event types inevitably erases type information at this boundary. Each handler deserializes with `ObjectMapper.readValue(payload, XxxEvent.class)`, knowing only its own event type.

---

## The SQS client — actual code

This follows the same existing SES/Secrets Manager client configuration pattern (the `AwsProperties`-based `AWS_ENDPOINT_URL` branch, static test credentials).

```java
// outbox/SqsConfig.java — actual code (excerpt)
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

The queue URL is read by `SqsProperties.domainEventQueueUrl()` from the `SQS_DOMAIN_EVENT_QUEUE_URL` environment variable (the `sqs.domain-event-queue-url` property) — fail-fast via `@NotBlank` + `@Validated` (see [config.md](config.md)).

```java
// config/SqsProperties.java — actual code
@ConfigurationProperties(prefix = "sqs")
@Validated
public record SqsProperties(@NotBlank String domainEventQueueUrl) {}
```

---

## LocalStack + Docker Compose — actual code

```yaml
# docker-compose.yml (excerpt)
localstack:
  environment:
    SERVICES: ses,secretsmanager,sqs
```

```bash
# localstack/init-sqs.sh — create the DLQ first, then attach it to the main queue via RedrivePolicy
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url .../domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

```env
# .env.example — added the SQS queue URL
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

The DLQ + `maxReceiveCount=3` follows the convention required by [scheduling.md — DLQ monitoring](../../../../docs/architecture/scheduling.md#dlq-monitoring) exactly — the same queue names and configuration as nestjs's `localstack/init-sqs.sh`, keeping consistency across languages.

---

## E2E tests — since this is asynchronous, verify with poll-and-timeout

`CardControllerE2ETest`/`PaymentControllerE2ETest`/`NotificationE2ETest` start a Testcontainers `LocalStackContainer` (including the SQS service), and at test start create the `domain-events`/`domain-events-dlq` queues directly via the SDK (`support/SqsTestQueue.java` — reproducing the same configuration as `localstack/init-sqs.sh` through SDK calls, since Testcontainers' `LocalStackContainer` doesn't mount local init scripts).

Card status transitions (account suspend/close → Card BC reaction), account balance changes (payment completed/cancelled/refunded → Account BC reaction), and email sending (account creation/deposit → SES) may all **still be incomplete at the moment the HTTP response is received** — they only actually take effect once `OutboxPoller` publishes to SQS on its next tick (up to 1 second later) and `OutboxConsumer` receives it. So instead of an immediate assert, a poll-and-timeout helper is built with [Awaitility](https://github.com/awaitility/awaitility):

```java
// CardControllerE2ETest.java — actual code (excerpt)
private void waitForCardStatus(String cardId, String expected, String ownerId) {
    await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() ->
                    assertThat(get("/cards/" + cardId, ownerId).getBody().get("status")).isEqualTo(expected));
}
```

`atMost(30 seconds)` leaves margin for the actual SQS+LocalStack round-trip latency (a 1-second polling interval + up to 5 seconds of long-poll waiting + container overhead). `untilAsserted` surfaces the final `AssertionError` (actual vs. expected) as-is on timeout, which helps debugging. `AccountControllerE2ETest` verifies the account's own state (suspended/reactivated/closed), which the Command Service changes synchronously at save time and so never goes through the Outbox — no polling is needed there. Polling is only needed for **values changed by another BC/Technical Service reacting to an event**.

---

## Event-handler idempotency

SQS guarantees at-least-once delivery. Since the same message can be **received more than once**, every `OutboxEventHandler` must be implemented to be **idempotent**.

The current Handlers already come close to **Level 1 (inherently idempotent)** — resending an email doesn't corrupt system state (though duplicate emails themselves can still occur). For full idempotency, apply Level 2 (a Ledger). This repository already has `account/infrastructure/notification/persistence/SentEmail` (a send-history Entity), so adding just an `eventId` column would let it double as a Ledger. `DepositByPaymentService`/`WithdrawByPaymentService` actually use a Level 2 Ledger keyed on `referenceId` (`hasTransactionWithReference`).

For the details of the 3-level strategy, see [root domain-events.md — Event handler idempotency](../../../../docs/architecture/domain-events.md#event-handler-idempotency).

---

## Harness verification

`harness/src/rules/OutboxDrainOrder.java` (rule: `outbox-drain-order`) fails the build if it finds a reference to the `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` symbols, or a call to `processPending()`/`poll()`/`drainOnce()`, inside a Command Service (`application/command/`). `harness/src/rules/SharedInfra.java` (rule: `shared-infra`) checks that if there's an `OutboxWriter` reference, `OutboxWriter.java`/`OutboxPoller.java`/`OutboxConsumer.java` all exist under `outbox/` — both rules are regression-tested with fixtures (`harness/test/testdata/`).

---

## Principle summary

- **How the Aggregate collects events** (`domainEvents` + `pullDomainEvents()`) matches the root.
- **The publish/receive mechanism is asynchronous via SQS**: the Outbox write inside `AccountRepositoryImpl.saveAccount()` (same transaction) → `OutboxPoller` (`@Scheduled(fixedDelay=1000)`) publishes to SQS → `OutboxConsumer` (long polling, `SmartLifecycle`) receives and invokes the matching `OutboxEventHandler` implementation in `application/event/`.
- **The Command Service never references `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` at all** — it returns immediately after saving. The harness's `outbox-drain-order` rule prevents any synchronous call from being added.
- **The `OutboxEventHandler` interface and each domain's implementation are independent of the routing mechanism** — `OutboxConsumer` reuses Spring's automatic `List<OutboxEventHandler>` collection as-is.
- **EventHandlers are not yet fully idempotent** — they qualify as inherently idempotent (resending an email is safe), but adding an `eventId` to `SentEmail` for a Level 2 Ledger is a follow-up task.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the Aggregate's responsibility for collecting events
- [cqrs-pattern.md](cqrs-pattern.md) — the boundary between the Command Service and event publishing
- [repository-pattern.md](repository-pattern.md) — the Outbox write inside the Repository
- [scheduling.md](scheduling.md) — the `@Scheduled`/DLQ/idempotency conventions (what this document actually implements)
- [persistence.md](persistence.md) — transactional atomicity between the Outbox write and the Aggregate save
- [graceful-shutdown.md](graceful-shutdown.md) — `OutboxConsumer`'s graceful shutdown via `SmartLifecycle.stop()`
- [local-dev.md](local-dev.md) — LocalStack SQS configuration
- [shared-modules.md](shared-modules.md) — placement convention for the `outbox/` package
