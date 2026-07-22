# Domain Events — Kotlin Spring Boot

> For the framework-agnostic principles, see [root domain-events.md](../../../../docs/architecture/domain-events.md).

## The Outbox pattern — actually implemented in `examples/`

The root clearly specifies: *"Outbox writes happen inside the Repository.save() transaction, queue
publishing is done by an independently, periodically running Poller, and EventHandler execution is
done by a Consumer that waits on the queue — this is the only path."* This repository's `examples/`
implements this rule as-is — the Command Service never synchronously drains within the same process
right after the save commits. `OutboxPoller` independently publishes from the Outbox table to SQS on a
1-second cycle, and `OutboxConsumer` waits on SQS and calls the handler through
`EventHandlerRegistry`.

```kotlin
// application/command/DepositService.kt — actual code
@Service
class DepositService(
    private val accountRepository: AccountRepository,
) {
    fun deposit(command: DepositCommand): TransactionResult {
        val (accounts, _) = accountRepository.findAccounts(
            AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
        )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.deposit(command.amount)
        accountRepository.saveAccount(account)     // ← commits the Aggregate + the Outbox row in one transaction
        return TransactionResult(/* ... */)
        // Ends here — OutboxRelay/OutboxPoller/OutboxConsumer are never called.
        // Publishing from the Outbox to SQS, and executing the EventHandler, are handled independently
        // by two separate components.
    }
}
```

The Command Service depends only on `AccountRepository` — it never references any of `OutboxRelay`/
`OutboxPoller`/`OutboxConsumer`. The harness's `outbox-no-sync-drain` rule catches it as a failure if
a Command Service references any of these three classes or calls `processPending`/`poll`/`drainOnce`.

---

## Step 1: the Aggregate collects events

```kotlin
// domain/DomainEvent.kt — actual code
sealed interface DomainEvent {
    val accountId: String
    val email: String
}

// domain/Account.kt — actual code
private val domainEvents: MutableList<DomainEvent> = mutableListOf()

fun deposit(amount: Long): Transaction {
    // ... invariant validation ...
    domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
    return transaction
}

fun pullDomainEvents(): List<DomainEvent> = domainEvents.toList().also { domainEvents.clear() }
```

All 6 `data class`es — `AccountCreatedEvent`/`MoneyDepositedEvent`/`MoneyWithdrawnEvent`/
`AccountSuspendedEvent`/`AccountReactivatedEvent`/`AccountClosedEvent` — implement `DomainEvent`. Only
the commonly shared `accountId`/`email` are specified in the contract; the differently-named timestamp
field each event has (`createdAt`/`suspendedAt`/`reactivatedAt`/`closedAt`) is not unified. Grouping
them under a `sealed interface` means the compiler checks exhaustiveness on any `when` branch that
handles these 6 types (whether in the Aggregate or a Handler, in any future code) — if a new event type
is added and a branch handling it is missed, it's caught at compile time.

The Payment/Card BC also collects each of their own Domain Events the same way (`PaymentCompletedEvent`/
`PaymentCancelledEvent`/`RefundApprovedEvent`, etc).

---

## Step 2: the Repository saves the Aggregate + Outbox in one transaction

```kotlin
// outbox/OutboxEvent.kt — actual code
@Entity
@Table(name = "outbox_events")
class OutboxEvent protected constructor() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Column(nullable = false, unique = true)
    var eventId: String = ""
        private set

    @Column(nullable = false)
    var eventType: String = ""
        private set

    @Column(nullable = false, columnDefinition = "TEXT")
    var payload: String = ""
        private set

    @Column(nullable = false)
    var processed: Boolean = false
        private set

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        private set

    companion object {
        fun from(event: Any, objectMapper: ObjectMapper): OutboxEvent =
            OutboxEvent().apply {
                this.eventId = UUID.randomUUID().toString().replace("-", "")
                this.eventType = (event as? IntegrationEventContract)?.eventName ?: (event::class.simpleName ?: "Unknown")
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() { processed = true }
}
```

`processed` means **"`OutboxPoller` finished publishing to SQS"**. Retries/at-least-once guarantees
beyond that point are handled not by this column but by SQS's visibility timeout + DLQ.

```kotlin
// outbox/OutboxWriter.kt — actual code
@Component
class OutboxWriter(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) {
    fun saveAll(events: List<Any>) {
        if (events.isEmpty()) return
        outboxEventJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper) })
    }
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — the actual saveAccount()
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository {

    @Transactional   // saving the Account/Transaction + saving the Outbox is one transaction
    override fun saveAccount(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
        outboxWriter.saveAll(account.pullDomainEvents())
    }
}
```

**The Command Service never calls `eventPublisher.publishEvent()`** — saving to the Outbox is the
Repository's internal responsibility, and a single `saveAccount()` call atomically commits the
Aggregate state and the events together, or rolls both back together (avoiding the dual-write problem).

### The `@Transactional` boundary — lives on `saveAccount()`, not the Command Service

`@Transactional` is on `AccountRepositoryImpl.saveAccount()`, not the Command Service — Spring AOP's
proxy intercepts the call between two different beans (Command Service → Repository) and enforces the
transaction boundary, so by the time the `accountRepository.saveAccount(account)` call returns, the
commit has actually already completed. This boundary is unrelated to when `OutboxPoller` separately
reads this row — the Command Service just returns once the save is done, and `OutboxPoller` discovers
this row on its next tick (at most 1 second later).

---

## Step 3: `EventHandlerRegistry` — handler routing

`outbox/EventHandlerRegistry.kt` — a `Map`-based registry that maps an `eventType` string to a handler
function. `OutboxPoller` (publishing) and `OutboxConsumer` (receiving/executing) collaborate through
this registry.

It's built up-front as a single `Map<String, (eventId, payload) -> Unit>` at constructor-execution
time, and `OutboxConsumer` looks it up. Rather than the `List<Interface>` pattern Kotlin/Spring
auto-collects (java-springboot's approach), each handler is injected as an individual constructor
parameter, so this file has to be edited by hand when adding a new domain (the scaffolding generator's
`--wire` automates this).

```kotlin
// outbox/EventHandlerRegistry.kt — actual code (excerpt)
@Component
class EventHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    // ... the remaining Domain Event Handlers/Integration Event Controllers
) {
    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> = mapOf(
        "AccountCreatedEvent" to { eventId, payload ->
            accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
        },
        "MoneyDepositedEvent" to { eventId, payload ->
            moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
        },
        // ... the remaining mappings
    )

    fun dispatch(eventType: String, eventId: String, payload: String) {
        val handler = handlers[eventType]
        if (handler == null) { logger.warn("Unknown event type: {}", eventType); return }
        handler(eventId, payload)
    }
}
```

Account's 6 Domain Event Handlers are passed the Outbox row's `eventId` as-is — this value becomes the
Level 2 idempotency key described in "Event Handler Idempotency" below. The Payment/Refund Domain Event
Handlers and the Integration Event Controller don't use `eventId`, so they ignore the lambda parameter
with `_`.

---

## Step 4: `OutboxPoller` — sending Outbox → SQS

`outbox/OutboxPoller.kt` — runs standalone on a 1-second cycle via `@Scheduled(fixedDelay = 1000)`. It
never calls any EventHandler directly — its only responsibility is "carrying events accumulated in the
DB over to the queue."

```kotlin
// outbox/OutboxPoller.kt — actual code (excerpt)
@Component
class OutboxPoller(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    @Volatile private var polling = false   // don't run overlapping ticks if the previous one hasn't finished

    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun poll() {
        if (polling) return
        polling = true
        try { drainOnce() } catch (e: Exception) { logger.error("Outbox polling failed", e) } finally { polling = false }
    }

    private fun drainOnce() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching {
                sqsClient.sendMessage(
                    SendMessageRequest.builder()
                        .queueUrl(sqsProperties.domainEventQueueUrl)
                        .messageBody(row.payload)
                        .messageAttributes(mapOf(
                            "eventType" to MessageAttributeValue.builder().dataType("String").stringValue(row.eventType).build(),
                            "eventId" to MessageAttributeValue.builder().dataType("String").stringValue(row.eventId).build(),
                        )).build(),
                )
            }.onSuccess { row.markProcessed() }
                .onFailure { logger.error("SQS publish failed: eventType=${row.eventType}", it) }
        }
    }
}
```

A row that fails to publish stays `processed=false` and is retried on the next tick (at most 1 second
later) — this is the last point where a failure remains subject to retry by the outbox table itself
(retries after a successful publish are SQS's job).

`AccountServiceApplication` must have `@EnableScheduling` for `@Scheduled` to work.

**Choice of polling interval**: 1 second. The same value as nestjs's `@Interval(1000)`, kept consistent
across languages. The overlap-prevention flag (`polling`) keeps this safe even if processing takes
longer than 1 second.

---

## Step 5: `OutboxConsumer` — receiving SQS → EventHandler

`outbox/OutboxConsumer.kt` — waits on SQS via long polling
(`ReceiveMessageRequest.waitTimeSeconds(5)`), and when it receives a message, looks up the handler from
`EventHandlerRegistry` by `eventType` (`MessageAttributes`) and calls it.

This repository doesn't use coroutines (see [scheduling.md](scheduling.md) — it uses traditional
thread-based execution that naturally meshes with Spring MVC + JPA's blocking stack). The reason it's
not expressed with `@Scheduled` like `OutboxPoller` is that `@Scheduled` is the right abstraction for
work that "runs briefly on a fixed interval and returns," whereas this Consumer is a single long loop
that blocks for `waitTimeSeconds(5)` and repeats indefinitely — a dedicated thread more directly
expresses this loop's intent (a background worker started exactly once at app bootstrap).

```kotlin
// outbox/OutboxConsumer.kt — actual code (excerpt)
@Component
class OutboxConsumer(
    private val sqsClient: SqsClient,
    private val registry: EventHandlerRegistry,
    private val sqsProperties: SqsProperties,
) : SmartLifecycle {
    @Volatile private var running = false
    private var workerThread: Thread? = null

    override fun start() {
        running = true
        workerThread = Thread(::pollLoop, "outbox-consumer").apply { start() }
    }

    override fun stop() {
        running = false
        workerThread?.join(SHUTDOWN_JOIN_TIMEOUT_MS)
    }

    override fun isRunning(): Boolean = running

    private fun pollLoop() {
        while (running) {
            val result = sqsClient.receiveMessage(
                ReceiveMessageRequest.builder()
                    .queueUrl(sqsProperties.domainEventQueueUrl)
                    .maxNumberOfMessages(10)
                    .messageAttributeNames("eventType", "eventId")
                    .waitTimeSeconds(5)
                    .build(),
            )
            result.messages().forEach { handle(it) }
        }
    }

    private fun handle(message: Message) {
        val eventType = message.messageAttributes()["eventType"]?.stringValue()
        val eventId = message.messageAttributes()["eventId"]?.stringValue() ?: ""
        try {
            if (eventType == null) throw IllegalStateException("Missing eventType message attribute.")
            registry.dispatch(eventType, eventId, message.body())
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(sqsProperties.domainEventQueueUrl).receiptHandle(message.receiptHandle()).build())
        } catch (e: Exception) {
            logger.error("Event processing failed: eventType=$eventType", e)
            // Not deleted — it will be received again and retried after the visibility timeout.
        }
    }
}
```

**Why `SmartLifecycle` instead of `@EventListener`/`DisposableBean`**: `@EventListener` is the marker
that means "this is a Domain Event Handler" in this repository — the harness's `event-placement` rule
enforces that a class using `@EventListener` is placed in `application/event/`. Using the same
annotation for a framework lifecycle callback (app startup complete/shutdown) would overlap in meaning,
so start/stop is instead expressed with `SmartLifecycle.start()`/`stop()` — `start()` is called right
after the context comes up, and `stop()` on Graceful Shutdown (as part of the context-shutdown
lifecycle).

Handler succeeds → message deleted (ack). Handler fails (or no handler is registered) → not deleted →
received again and retried after SQS's visibility timeout (at-least-once).

---

## The `application/event/` Handler

The handler's own code is independent of the call path (`OutboxConsumer` → `EventHandlerRegistry`) — it
only needs to contain the per-event-type processing logic.

```kotlin
// account/application/event/AccountCreatedEventHandler.kt — actual code
@Component
class AccountCreatedEventHandler(private val notificationService: NotificationService) {
    fun handle(event: AccountCreatedEvent, eventId: String) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountCreated",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] Your account has been opened",
            body = "Account (${event.accountId}) has been opened. Currency: ${event.currency}",
        )
    }
}
```

A Handler never catches exceptions — instead of swallowing a failure, it lets it propagate as-is, so
`OutboxConsumer` doesn't delete that message and leaves it to SQS redelivery. `eventId` (the Outbox
row's `eventId`) is passed straight through to `NotificationService.sendEmail()` as `sourceEventId`,
used as the Level 2 idempotency key.

The harness's `event-placement` rule checks that a file with a `*EventHandler` suffix lives inside the
`application/event/` package — all 6 Handlers pass this rule as-is.

### The Integration Event conversion handler — the asynchronous re-drain boundary

There are handlers, like `AccountSuspendedEventHandler`/`AccountClosedEventHandler`, that receive a
Domain Event and write a new Integration Event to the Outbox (`OutboxWriter.saveAll(...)`). This
handler itself is the callback that runs when `OutboxConsumer` receives `AccountSuspendedEvent`, and
the Integration Event row it writes here isn't processed further right there in place — it's separately
published to SQS on the next `OutboxPoller` tick (at most 1 second later), and `OutboxConsumer` consumes
it again. It can take two Poller/Consumer round trips (at least a few hundred ms to a few seconds)
before one Domain Event makes its way to an external BC's Integration Event — this is why the E2E tests
verify it by polling (see the `await*` helper in each `*E2ETest.kt`).

---

## Event handler idempotency

SQS guarantees at-least-once delivery. Since the same message may be **received more than once**, every
EventHandler must be implemented **idempotently**.

> **Note**: the same at-least-once premise applies to the Task Queue's `@TaskConsumer` too, which
> provides a 3-tier model there — **a framework-level ledger (the `idempotencyKey` option)** and
> **Level 3 strong atomicity**, among others. This is a pattern common to both EventHandlers and Task
> Controllers; see [scheduling.md — Task idempotency](./scheduling.md#task-idempotency) for the detailed
> structure. Applying the same ledger strategy is recommended for an EventHandler too when its side
> effects are significant (re-charging a payment, calling an external API, etc). This repository's
> `DepositByPaymentCommandHandler`/`WithdrawByPaymentCommandHandler` actually use a `referenceId`-based
> Level 2 Ledger (`hasTransactionWithReference`).

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — actual code
override fun sendEmail(
    accountId: String,
    eventType: String,
    sourceEventId: String,
    recipient: String,
    subject: String,
    body: String,
) {
    if (sentEmailJpaRepository.existsBySourceEventId(sourceEventId)) {
        logger.atInfo() /* ... */ .log("Event already sent — skipping duplicate send")
        return
    }
    // ... send via SES ...
    sentEmailJpaRepository.save(SentEmail.create(accountId, eventType, sourceEventId, recipient, subject, sesMessageId))
}
```

`sourceEventId` is the Outbox row's `eventId` that `EventHandlerRegistry.dispatch()` passes through —
the `sent_emails` table's `source_event_id` column (a unique constraint) plays the role of the Ledger.
All 6 EventHandlers receive `eventId` via `handle(event, eventId)` and pass it straight through.

See [root domain-events.md — Event Handler Idempotency](../../../../docs/architecture/domain-events.md#event-handler-idempotency) for the detailed 3-tier strategy.

---

## The SQS client — actual code

Modeled directly on the existing SES client setup (`SesConfig`) — the `AWS_ENDPOINT_URL` branch, static
test credentials. Since `outbox/` is shared infrastructure that belongs to no BC, this configuration
also lives under `outbox/`.

```kotlin
// outbox/SqsConfig.kt — actual code
@Configuration
class SqsConfig(private val awsProperties: AwsProperties) {
    @Bean
    fun sqsClient(): SqsClient {
        val builder = SqsClient.builder()
            .region(Region.of(awsProperties.region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(awsProperties.accessKeyId, awsProperties.secretAccessKey)))
        if (awsProperties.endpointUrl.isNotBlank()) builder.endpointOverride(URI.create(awsProperties.endpointUrl))
        return builder.build()
    }
}
```

The queue URL is read by `config/SqsProperties.kt` from `sqs.domain-event-queue-url` (the
`SQS_DOMAIN_EVENT_QUEUE_URL` environment variable).

---

## LocalStack + Docker Compose — actual code

```yaml
# docker-compose.yml (excerpt)
localstack:
  environment:
    SERVICES: ses,secretsmanager,sqs
```

```bash
# localstack/init-sqs.sh — create the DLQ first, then connect it to the main queue via RedrivePolicy
awslocal sqs create-queue --queue-name domain-events-dlq
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url .../domain-events-dlq \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
awslocal sqs create-queue --queue-name domain-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"'"$DLQ_ARN"'\",\"maxReceiveCount\":\"3\"}"}'
```

```env
# .env.development — added the SQS queue URL
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

The DLQ + `maxReceiveCount=3` follows the convention required by [scheduling.md — DLQ Monitoring](../../../../docs/architecture/scheduling.md#dlq-monitoring) as-is.

The Testcontainers-based E2E tests don't reuse `docker-compose.yml`/`init-sqs.sh` — each test class
adds the SQS service to a `LocalStackContainer` and, in `@DynamicPropertySource`, creates the queue
directly with `SqsClient` and injects it as `SQS_DOMAIN_EVENT_QUEUE_URL` (the DLQ is omitted for testing
purposes) — since `SqsProperties.domainEventQueueUrl` is fail-fast validated with `@NotBlank`, every
`@SpringBootTest` that boots the full Spring context (Account/Card/Payment/Auth/Notification E2E) must
have this configuration set up without exception.

---

## Principle summary

- **How the Aggregate collects events** (`domainEvents` + `pullDomainEvents()`) stays as-is. Event types implement the shared `sealed interface DomainEvent`.
- **The publishing mechanism is Outbox-based**: the Repository commits an Outbox row in the same transaction as saving the Aggregate. The Command Service returns immediately after saving — it never calls `OutboxRelay`/`OutboxPoller`/`OutboxConsumer` directly (verified by the harness's `outbox-no-sync-drain`).
- **Delivery goes Outbox → SQS → Handler in that order**: `OutboxPoller` (publishing) and `OutboxConsumer` (receiving/executing) run periodically, independent of each other — there is no synchronous drain within the same process anywhere in this repository.
- **`EventHandlerRegistry` handles routing**: an `eventType` → handler-function `Map` — looked up every time `OutboxConsumer` receives an SQS message.
- **An EventHandler never swallows an exception** — a failed message is redelivered by SQS (implementing the Handler idempotently, assuming at-least-once delivery, is the ideal).
- **`OutboxConsumer` starts/stops via `SmartLifecycle`** — it never mixes `@EventListener` (which means "Domain Event Handler" here) with a framework lifecycle callback.

### Related documents

- [tactical-ddd.md](tactical-ddd.md) — the Aggregate's responsibility for collecting events
- [cqrs-pattern.md](cqrs-pattern.md) — the boundary between the Command Service and event publishing
- [repository-pattern.md](repository-pattern.md) — saving to the Outbox from the Repository
- [persistence.md](persistence.md) — why the `@Transactional` boundary was moved to the Repository
- [scheduling.md](scheduling.md) — why `@Scheduled`/coroutines aren't used, DLQ/idempotency conventions
- [local-dev.md](local-dev.md) — the LocalStack SQS setup
