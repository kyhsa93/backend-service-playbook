# 도메인 이벤트 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root domain-events.md](../../../../docs/architecture/domain-events.md) 참조.

## Outbox 패턴 — `examples/`에 실제로 구현되어 있다

root는 명확히 규정한다: *"Outbox 적재는 Repository.save() 트랜잭션 안에서, 큐 발행은 독립적으로
주기 실행되는 Poller가, EventHandler 실행은 큐를 수신 대기하는 Consumer가 — 이 경로가 유일하다."*
이 저장소의 `examples/`는 이 규칙을 그대로 구현한다 — Command Service가 저장 커밋 직후 같은
프로세스 안에서 동기적으로 드레인하던 이전 방식은 **더 이상 존재하지 않는다**. `OutboxPoller`가
독립적으로 1초 주기로 Outbox 테이블을 SQS로 발행하고, `OutboxConsumer`가 SQS를 수신 대기하다가
`EventHandlerRegistry`를 통해 핸들러를 호출한다.

```kotlin
// application/command/DepositService.kt — 실제 코드
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
        accountRepository.saveAccount(account)     // ← Aggregate + Outbox 행을 한 트랜잭션에 커밋
        return TransactionResult(/* ... */)
        // 여기서 끝난다 — OutboxRelay/OutboxPoller/OutboxConsumer를 호출하지 않는다.
        // Outbox → SQS 발행과 EventHandler 실행은 두 컴포넌트가 독립적으로 처리한다.
    }
}
```

Command Service는 `AccountRepository`에만 의존한다 — `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`
어느 것도 참조하지 않는다. harness의 `outbox-no-sync-drain` 규칙이 Command Service가 이 세 클래스를
참조하거나 `processPending`/`poll`/`drainOnce`를 호출하면 실패로 잡아낸다.

---

## 1단계: Aggregate에서 이벤트 수집

```kotlin
// domain/DomainEvent.kt — 실제 코드
sealed interface DomainEvent {
    val accountId: String
    val email: String
}

// domain/Account.kt — 실제 코드
private val domainEvents: MutableList<DomainEvent> = mutableListOf()

fun deposit(amount: Long): Transaction {
    // ... 불변식 검증 ...
    domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
    return transaction
}

fun pullDomainEvents(): List<DomainEvent> = domainEvents.toList().also { domainEvents.clear() }
```

`AccountCreatedEvent`/`MoneyDepositedEvent`/`MoneyWithdrawnEvent`/`AccountSuspendedEvent`/`AccountReactivatedEvent`/`AccountClosedEvent` 6개 `data class`가 모두 `DomainEvent`를 구현한다. 공통으로 갖는 `accountId`/`email`만 계약에 명시하고, 이벤트별로 이름이 다른 타임스탬프 필드(`createdAt`/`suspendedAt`/`reactivatedAt`/`closedAt`)는 공통화하지 않는다. `sealed interface`로 묶으면 이 6개 타입을 다루는 `when` 분기(Aggregate/Handler 어디든 추가될 향후 코드)에서 컴파일러가 완전성(exhaustiveness)을 검사한다 — 새 이벤트 타입이 추가되는데 분기 처리를 빠뜨리면 컴파일 타임에 잡힌다.

Payment/Card BC도 각자의 Domain Event를 같은 방식으로 수집한다(`PaymentCompletedEvent`/`PaymentCancelledEvent`/`RefundApprovedEvent` 등).

---

## 2단계: Repository에서 Aggregate + Outbox를 한 트랜잭션으로 저장 (구현됨)

```kotlin
// outbox/OutboxEvent.kt — 실제 코드
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

`processed`의 의미가 바뀌었다: 예전에는 "핸들러가 처리를 끝냈다"였지만, 지금은 **"`OutboxPoller`가
SQS로 발행을 끝냈다"**는 뜻이다. 이후의 재시도/at-least-once 보장은 이 컬럼이 아니라 SQS의
visibility timeout + DLQ가 담당한다.

```kotlin
// outbox/OutboxWriter.kt — 실제 코드
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
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 saveAccount()
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val outboxWriter: OutboxWriter,
    private val em: EntityManager,
) : AccountRepository {

    @Transactional   // Account/Transaction 저장 + Outbox 저장이 하나의 트랜잭션
    override fun saveAccount(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
        outboxWriter.saveAll(account.pullDomainEvents())
    }
}
```

**Command Service는 `eventPublisher.publishEvent()`를 호출하지 않는다** — Outbox 저장은 Repository 내부 책임이며, `saveAccount()` 호출 하나로 Aggregate 상태와 이벤트가 원자적으로 커밋되거나 함께 롤백된다(dual-write 문제 회피).

### `@Transactional` 경계 — Command Service가 아니라 `saveAccount()`에 있다

`@Transactional`은 Command Service가 아니라 `AccountRepositoryImpl.saveAccount()`에 있다 — Spring
AOP 프록시가 서로 다른 빈(Command Service → Repository) 사이의 호출을 가로채 트랜잭션 경계를
강제하므로, `accountRepository.saveAccount(account)` 호출이 반환하는 시점에 실제로 커밋이 끝나
있다. 이 경계는 `OutboxPoller`가 별도로 언제 이 행을 읽어가는지와 무관하다 — Command Service는
저장이 끝나면 그대로 반환하고, `OutboxPoller`는 다음 tick(최대 1초 후)에 이 행을 발견한다.

---

## 3단계: `EventHandlerRegistry` — 핸들러 라우팅 (구현됨)

`outbox/EventHandlerRegistry.kt` — `eventType` 문자열을 핸들러 함수에 매핑하는 `Map` 기반
레지스트리다. `OutboxPoller`(발행)와 `OutboxConsumer`(수신·실행)가 이 레지스트리를 통해 협업한다.

예전 `OutboxRelay.dispatch()`의 `when(eventType)` 하드코딩 분기를 대체한다 — 라우팅 로직 자체는
거의 동일하지만(`eventType` 문자열 → 핸들러 호출), 지금은 생성자 실행 시점에 `Map<String,
(eventId, payload) -> Unit>` 하나로 미리 구성해 두고 `OutboxConsumer`가 조회한다. Kotlin/Spring이
자동 수집하는 `List<Interface>` 패턴(java-springboot의 방식)이 아니라 여전히 생성자에 각 핸들러를
개별 파라미터로 주입받는다 — 새 도메인을 추가할 때 이 파일을 직접 고쳐야 하는 점은 예전과 같다
(스캐폴딩 생성기의 `--wire`가 이를 자동화한다).

```kotlin
// outbox/EventHandlerRegistry.kt — 실제 코드(발췌)
@Component
class EventHandlerRegistry(
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    // ... 나머지 Domain Event Handler/Integration Event Controller들
) {
    private val handlers: Map<String, (eventId: String, payload: String) -> Unit> = mapOf(
        "AccountCreatedEvent" to { eventId, payload ->
            accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
        },
        "MoneyDepositedEvent" to { eventId, payload ->
            moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
        },
        // ... 나머지 매핑
    )

    fun dispatch(eventType: String, eventId: String, payload: String) {
        val handler = handlers[eventType]
        if (handler == null) { logger.warn("알 수 없는 이벤트 타입: {}", eventType); return }
        handler(eventId, payload)
    }
}
```

Account 6개 Domain Event Handler는 Outbox 행의 `eventId`를 그대로 전달받는다 — 이 값이 아래
"이벤트 핸들러 멱등성"의 Level 2 멱등성 키가 된다. Payment/Refund Domain Event Handler와
Integration Event Controller는 `eventId`를 쓰지 않으므로 람다 파라미터를 `_`로 무시한다.

---

## 4단계: `OutboxPoller` — Outbox → SQS 전송 (구현됨)

`outbox/OutboxPoller.kt` — `@Scheduled(fixedDelay = 1000)`으로 1초 주기 단독 실행된다. 어떤
EventHandler도 직접 호출하지 않는다 — "DB에 쌓인 이벤트를 큐로 실어 나른다"는 책임만 갖는다.

```kotlin
// outbox/OutboxPoller.kt — 실제 코드(발췌)
@Component
class OutboxPoller(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    private val sqsProperties: SqsProperties,
) {
    @Volatile private var polling = false   // 이전 tick이 안 끝났으면 겹쳐 실행하지 않는다

    @Scheduled(fixedDelay = 1000)
    @Transactional
    fun poll() {
        if (polling) return
        polling = true
        try { drainOnce() } catch (e: Exception) { logger.error("Outbox 폴링 실패", e) } finally { polling = false }
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
                .onFailure { logger.error("SQS 발행 실패: eventType=${row.eventType}", it) }
        }
    }
}
```

발행 실패 행은 `processed=false`로 남아 다음 tick(최대 1초 후)에 재시도된다 — 실패가 outbox
테이블 자체의 재시도 대상으로 남는 마지막 지점이다(발행에 성공한 이후의 재시도는 SQS의 몫).

`AccountServiceApplication`에 `@EnableScheduling`이 있어야 `@Scheduled`가 동작한다.

**폴링 주기 선택**: 1초. nestjs의 `@Interval(1000)`과 동일한 값으로 언어 간 일관성을 유지한다.
겹쳐 실행 방지(`polling` 플래그)로 처리 시간이 1초를 넘는 경우에도 안전하다.

---

## 5단계: `OutboxConsumer` — SQS → EventHandler 수신 (구현됨)

`outbox/OutboxConsumer.kt` — SQS를 long polling(`ReceiveMessageRequest.waitTimeSeconds(5)`)으로
수신 대기하다가 메시지를 받으면 `eventType`(`MessageAttributes`)으로 `EventHandlerRegistry`에서
핸들러를 찾아 호출한다.

이 저장소는 코루틴을 쓰지 않는다([scheduling.md](scheduling.md) 참고 — Spring MVC + JPA의
블로킹 스택과 자연스럽게 맞물리는 전통적인 스레드 기반 실행을 쓴다). `OutboxPoller`처럼
`@Scheduled`로 표현하지 않은 이유는, `@Scheduled`가 "일정 주기로 짧게 실행하고 반환"하는 작업에
맞는 추상화인 반면 이 Consumer는 `waitTimeSeconds(5)` 동안 블로킹하며 무한히 반복하는 하나의 긴
루프이기 때문이다 — 전용 스레드가 이 루프의 의도(앱 부트스트랩 시 단 한 번 시작되는 백그라운드
워커)를 더 직접적으로 표현한다.

```kotlin
// outbox/OutboxConsumer.kt — 실제 코드(발췌)
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
            if (eventType == null) throw IllegalStateException("eventType 메시지 속성이 없습니다.")
            registry.dispatch(eventType, eventId, message.body())
            sqsClient.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(sqsProperties.domainEventQueueUrl).receiptHandle(message.receiptHandle()).build())
        } catch (e: Exception) {
            logger.error("이벤트 처리 실패: eventType=$eventType", e)
            // 삭제하지 않는다 — visibility timeout 이후 재수신되어 재시도된다.
        }
    }
}
```

**`@EventListener`/`DisposableBean`이 아니라 `SmartLifecycle`을 쓰는 이유**: `@EventListener`는
이 저장소에서 Domain Event Handler를 뜻하는 표식이다 — harness의 `event-placement` 규칙이
`@EventListener` 사용 클래스를 `application/event/`에 배치하도록 강제한다. 프레임워크 생명주기
콜백(앱 기동 완료/종료)에 같은 애노테이션을 쓰면 의미가 겹치므로, `SmartLifecycle.start()`/
`stop()`으로 시작·종료를 표현한다 — `start()`는 컨텍스트가 뜬 직후, `stop()`은 Graceful Shutdown
시(컨텍스트 종료 라이프사이클의 일부로) 호출된다.

핸들러 성공 → 메시지 삭제(ack). 핸들러 실패(또는 등록된 핸들러가 없음) → 삭제하지 않음 →
SQS의 visibility timeout 이후 재수신되어 재시도된다(at-least-once).

---

## `application/event/` Handler (변경 없음)

핸들러 자체의 코드는 예전과 동일하다 — 달라진 것은 "누가 호출하는가"뿐이다(예전: `OutboxRelay`,
지금: `OutboxConsumer` → `EventHandlerRegistry`).

```kotlin
// account/application/event/AccountCreatedEventHandler.kt — 실제 코드
@Component
class AccountCreatedEventHandler(private val notificationService: NotificationService) {
    fun handle(event: AccountCreatedEvent, eventId: String) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountCreated",
            sourceEventId = eventId,
            recipient = event.email,
            subject = "[Account] 계좌가 개설되었습니다",
            body = "계좌(${event.accountId})가 개설되었습니다. 통화: ${event.currency}",
        )
    }
}
```

Handler는 예외를 잡지 않는다 — 실패를 삼키지 않고 그대로 전파해, `OutboxConsumer`가 해당 메시지를
삭제하지 않고 SQS 재전달에 맡기게 한다. `eventId`(Outbox 행의 `eventId`)는 그대로
`NotificationService.sendEmail()`에 `sourceEventId`로 전달되어 Level 2 멱등성의 키로 쓰인다.

harness의 `event-placement` 규칙은 `*EventHandler` 접미사를 가진 파일이 `application/event/`
패키지 안에 있는지 검사한다 — 6개 Handler 모두 이 규칙을 그대로 통과한다.

### Integration Event 변환 핸들러 — 비동기 재드레인 경계

`AccountSuspendedEventHandler`/`AccountClosedEventHandler`처럼 Domain Event를 받아 Integration
Event를 새로 Outbox에 적재하는 핸들러가 있다(`OutboxWriter.saveAll(...)`). 이 핸들러 자체가
`OutboxConsumer`가 `AccountSuspendedEvent`를 수신했을 때 실행되는 콜백이고, 여기서 적재한
Integration Event 행은 그 자리에서 이어 처리되지 않는다 — 다음 `OutboxPoller` tick(최대 1초
후)에 별도로 SQS로 발행되어 다시 `OutboxConsumer`가 소비한다. 하나의 Domain Event가 외부 BC
Integration Event로 이어지기까지 두 번의 Poller/Consumer 왕복(최소 몇백 ms~수 초)이 걸릴 수 있다
— E2E 테스트가 이를 폴링으로 확인하는 이유(각 `*E2ETest.kt`의 `await*` 헬퍼 참고).

---

## 이벤트 핸들러 멱등성

SQS는 at-least-once 전달을 보장한다. 같은 메시지가 **중복 수신**될 수 있으므로 모든 EventHandler는 **멱등(idempotent)** 하게 구현해야 한다.

> **참고**: Task Queue의 `@TaskConsumer`에도 동일한 at-least-once 전제가 적용되며, 그쪽은 **프레임워크 레벨 ledger(`idempotencyKey` 옵션)**·**Level 3 강한 원자성** 등 3단계 모델을 제공한다. EventHandler와 Task Controller 모두에 공통되는 패턴이며 자세한 구조는 [scheduling.md — 멱등성](./scheduling.md#멱등성)을 참조한다. EventHandler도 부작용 큰 경우 (재결제·외부 API 호출 등) 동일한 ledger 전략 적용을 권장한다. 이 저장소의 `DepositByPaymentCommandHandler`/`WithdrawByPaymentCommandHandler`는 `referenceId` 기준 Level 2 Ledger(`hasTransactionWithReference`)를 실제로 쓴다.

```kotlin
// account/infrastructure/notification/NotificationServiceImpl.kt — 실제 코드
override fun sendEmail(
    accountId: String,
    eventType: String,
    sourceEventId: String,
    recipient: String,
    subject: String,
    body: String,
) {
    if (sentEmailJpaRepository.existsBySourceEventId(sourceEventId)) {
        logger.atInfo() /* ... */ .log("이미 발송된 이벤트 — 중복 발송 스킵")
        return
    }
    // ... SES 발송 ...
    sentEmailJpaRepository.save(SentEmail.create(accountId, eventType, sourceEventId, recipient, subject, sesMessageId))
}
```

`sourceEventId`는 `EventHandlerRegistry.dispatch()`가 넘겨주는 Outbox 행의 `eventId`다 —
`sent_emails` 테이블의 `source_event_id` 컬럼(unique 제약)이 Ledger 역할을 한다. 6개
EventHandler 모두 `handle(event, eventId)`로 `eventId`를 전달받아 그대로 넘긴다.

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## SQS 클라이언트 — 실제 코드

기존 SES 클라이언트 구성(`SesConfig`)을 그대로 본뜬다 — `AWS_ENDPOINT_URL` 분기, 정적 test
자격증명. `outbox/`는 어느 BC에도 속하지 않는 공유 인프라이므로 이 설정도 `outbox/` 아래 둔다.

```kotlin
// outbox/SqsConfig.kt — 실제 코드
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

큐 URL은 `config/SqsProperties.kt`가 `sqs.domain-event-queue-url`(`SQS_DOMAIN_EVENT_QUEUE_URL`
환경 변수)에서 읽는다.

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
# .env.development — SQS 큐 URL 추가
SQS_DOMAIN_EVENT_QUEUE_URL=http://localhost:4566/000000000000/domain-events
```

DLQ + `maxReceiveCount=3`은 [scheduling.md — DLQ 모니터링](../../../../docs/architecture/scheduling.md#dlq-모니터링)이 요구하는 컨벤션을 그대로 따른다.

Testcontainers 기반 E2E 테스트는 `docker-compose.yml`/`init-sqs.sh`를 재사용하지 않고, 각
테스트 클래스가 `LocalStackContainer`에 SQS 서비스를 추가하고 `@DynamicPropertySource`에서
`SqsClient`로 큐를 직접 만들어 `SQS_DOMAIN_EVENT_QUEUE_URL`로 주입한다(DLQ는 테스트 목적상
생략) — `SqsProperties.domainEventQueueUrl`이 `@NotBlank`로 fail-fast 검증되므로, 전체 Spring
컨텍스트를 띄우는 모든 `@SpringBootTest`(Account/Card/Payment/Auth/Notification E2E)가 예외 없이
이 설정을 갖춰야 한다.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 방식(`domainEvents` + `pullDomainEvents()`)은 그대로 유지한다. 이벤트 타입은 공통 `sealed interface DomainEvent`를 구현한다.
- **발행 메커니즘은 Outbox 기반이다**: Repository가 Aggregate 저장과 같은 트랜잭션에 Outbox 행을 커밋한다. Command Service는 저장 후 곧바로 반환한다 — `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 직접 호출하지 않는다(harness `outbox-no-sync-drain`이 검증).
- **Outbox → SQS → Handler 순서로 전달한다**: `OutboxPoller`(발행)와 `OutboxConsumer`(수신·실행)가 서로 독립적으로 주기 실행된다 — 같은 프로세스 안 동기 드레인은 이 저장소에 없다.
- **`EventHandlerRegistry`가 라우팅을 담당한다**: `eventType` → 핸들러 함수 `Map` — `OutboxConsumer`가 SQS 메시지를 수신할 때마다 조회한다.
- **EventHandler는 예외를 삼키지 않는다** — 실패한 메시지는 SQS가 재전달한다(at-least-once 전달을 전제로 Handler를 멱등하게 구현하는 것이 이상적이다).
- **`OutboxConsumer`는 `SmartLifecycle`로 시작/종료한다** — Domain Event Handler를 뜻하는 `@EventListener`와 프레임워크 생명주기 콜백을 혼용하지 않는다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [persistence.md](persistence.md) — `@Transactional` 경계를 Repository로 옮긴 이유
- [scheduling.md](scheduling.md) — `@Scheduled`/코루틴 미사용 이유, DLQ·멱등성 컨벤션
- [local-dev.md](local-dev.md) — LocalStack SQS 구성
