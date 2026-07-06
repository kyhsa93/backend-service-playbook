# 도메인 이벤트 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root domain-events.md](../../../../docs/architecture/domain-events.md) 참조.

## 알려진 갭 — 현재 예제는 Outbox 없이 동기 in-process 이벤트를 사용한다

root는 명확히 규정한다: *"Domain Event는 in-process 이벤트 버스를 사용하지 않는다. Outbox → 메시지 큐 → EventConsumer 경로로 전달된다."* 이 저장소의 `examples/`는 이 규칙을 따르지 않는다.

```kotlin
// application/command/CreateAccountService.kt — 현재 코드
accountRepository.save(account)
account.pullDomainEvents().forEach(eventPublisher::publishEvent)   // ← 동기 in-process 발행
```

```kotlin
// account/application/event/AccountNotificationListener.kt — 현재 코드
@Component
class AccountNotificationListener(private val notificationService: NotificationService) {
    @EventListener
    fun handleAccountCreated(event: AccountCreatedEvent) {
        runCatching { notificationService.sendEmail(/* ... */) }
            .onFailure { logNotificationFailure("AccountCreated", event.accountId, it) }
    }
    // ...
}
```

`ApplicationEventPublisher.publishEvent()`는 기본적으로 **같은 스레드, 같은 트랜잭션 내에서 동기적으로** 리스너를 호출한다. Outbox 테이블, at-least-once 전달, 멱등성 전략이 전혀 없다. 코드 주석이 "알림 발송 실패가 커맨드 자체를 실패시키지 않도록 try/catch"라고 명시하는 것에서 보듯 의도된 단순화이지 실수는 아니지만, root 패턴과의 괴리는 명확하다 — **아래는 root가 요구하는 올바른 Outbox 기반 패턴이며, `examples/`에는 아직 반영되어 있지 않다.**

---

## 1단계: Aggregate에서 이벤트 수집 (현재 코드 — 이 부분은 root와 일치)

```kotlin
// domain/Account.kt — 실제 코드
@Transient
private val domainEvents: MutableList<Any> = mutableListOf()

fun deposit(amount: Long): Transaction {
    // ... 불변식 검증 ...
    domainEvents += MoneyDepositedEvent(accountId, email, transaction.transactionId, money, balance, transaction.createdAt)
    return transaction
}

fun pullDomainEvents(): List<Any> = domainEvents.toList().also { domainEvents.clear() }
```

`@Transient`로 표시해 JPA가 이 필드를 컬럼으로 매핑하지 않도록 한다 — 이벤트는 영속 상태가 아니라 "아직 전달되지 않은 사실의 목록"이다.

### 개선 — `sealed interface`로 이벤트 계층 묶기

현재 `AccountCreatedEvent`, `MoneyDepositedEvent` 등은 서로 무관한 `data class`로, `domainEvents: MutableList<Any>`가 `Any`로 타입을 지운다. Kotlin에서는 **`sealed interface`**로 묶어 `when` 분기의 완전성을 컴파일러가 검사하게 만들 수 있다 — 이벤트 타입이 늘어나도 처리 누락(새 이벤트에 대응하는 핸들러 분기 빠뜨림)을 컴파일 타임에 잡는다.

```kotlin
// domain/AccountDomainEvent.kt — 제안
sealed interface AccountDomainEvent {
    val accountId: String
    val email: String
}

data class AccountCreatedEvent(
    override val accountId: String,
    val ownerId: String,
    override val email: String,
    val currency: String,
    val createdAt: LocalDateTime,
) : AccountDomainEvent

data class MoneyDepositedEvent(
    override val accountId: String,
    override val email: String,
    val transactionId: String,
    val amount: Money,
    val balanceAfter: Money,
    val occurredAt: LocalDateTime,
) : AccountDomainEvent
```

```kotlin
// domain/Account.kt — MutableList<Any> 대신 봉인된 타입 사용
@Transient
private val domainEvents: MutableList<AccountDomainEvent> = mutableListOf()

fun pullDomainEvents(): List<AccountDomainEvent> = domainEvents.toList().also { domainEvents.clear() }
```

```kotlin
// EventHandler에서 when으로 완전성 검사
fun handle(event: AccountDomainEvent) = when (event) {
    is AccountCreatedEvent -> handleCreated(event)
    is MoneyDepositedEvent -> handleDeposited(event)
    is MoneyWithdrawnEvent -> handleWithdrawn(event)
    is AccountSuspendedEvent -> handleSuspended(event)
    is AccountReactivatedEvent -> handleReactivated(event)
    is AccountClosedEvent -> handleClosed(event)
    // 새 이벤트 타입 추가 시 분기 누락하면 컴파일 에러 (exhaustive when)
}
```

---

## 2단계: Repository에서 Aggregate + Outbox를 한 트랜잭션으로 저장

```kotlin
// outbox/OutboxEvent.kt — 신규 Entity
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
        fun from(event: AccountDomainEvent, objectMapper: ObjectMapper): OutboxEvent =
            OutboxEvent().apply {
                this.eventId = UUID.randomUUID().toString().replace("-", "")
                this.eventType = event::class.simpleName ?: "Unknown"
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() {
        processed = true
    }
}
```

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 올바른 save() (제안)
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val outboxJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
) : AccountRepository {

    @Transactional   // Account 저장 + Outbox 저장이 하나의 트랜잭션
    override fun save(account: Account) {
        jpaRepository.save(account)
        val events = account.pullDomainEvents()
        if (events.isNotEmpty()) {
            outboxJpaRepository.saveAll(events.map { OutboxEvent.from(it, objectMapper) })
        }
    }
}
```

**Command Service는 더 이상 `eventPublisher.publishEvent()`를 호출하지 않는다** — Outbox 저장은 Repository 내부 책임이며, `save()` 호출 하나로 Aggregate 상태와 이벤트가 원자적으로 커밋되거나 함께 롤백된다.

---

## 3단계: OutboxRelay — 폴링 후 메시지 큐 발행

```kotlin
// outbox/OutboxRelay.kt
@Component
class OutboxRelay(
    private val outboxJpaRepository: OutboxEventJpaRepository,
    private val sqsClient: SqsClient,
    @Value("\${app.outbox.queue-url}") private val queueUrl: String,
) {
    private val logger = KotlinLogging.logger {}

    @Scheduled(fixedDelay = 1000)   // 1초 간격 폴링 — Scheduler는 Infrastructure 레이어
    fun relay() {
        val pending = outboxJpaRepository.findTop100ByProcessedFalseOrderByCreatedAtAsc()
        pending.forEach { event ->
            runCatching {
                sqsClient.sendMessage {
                    it.queueUrl(queueUrl)
                        .messageBody(event.payload)
                        .messageGroupId(event.eventType)
                        .messageDeduplicationId(event.eventId)
                }
                event.markProcessed()
                outboxJpaRepository.save(event)
            }.onFailure { logger.error(it) { "Outbox relay 실패: eventId=${event.eventId}" } }
        }
    }
}
```

큐 발행 실패 시 `processed`가 갱신되지 않으므로 다음 폴링에서 재시도된다 — at-least-once 전달이 여기서부터 시작된다.

---

## 4단계: EventConsumer → application/event/ Handler

메시지 큐에서 수신한 이벤트는 `application/event/`의 Handler가 처리한다. 현재 `AccountNotificationListener`의 역할과 동일하지만, 호출 경로가 동기 `@EventListener`가 아니라 **비동기 메시지 큐 Consumer**로 바뀐다.

```kotlin
// outbox/EventConsumer.kt — SQS 폴링 → eventType으로 라우팅
@Component
class EventConsumer(
    private val sqsClient: SqsClient,
    private val handlerRegistry: Map<String, EventHandler<*>>,   // eventType -> Handler
    @Value("\${app.outbox.queue-url}") private val queueUrl: String,
) {
    @Scheduled(fixedDelay = 500)
    fun poll() {
        val messages = sqsClient.receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(10) }.messages()
        messages.forEach { message ->
            val eventType = /* 메시지 속성에서 추출 */ ""
            handlerRegistry[eventType]?.handle(message.body())
            sqsClient.deleteMessage { it.queueUrl(queueUrl).receiptHandle(message.receiptHandle()) }
        }
    }
}
```

---

## 이벤트 핸들러 멱등성

메시지 큐는 at-least-once 전달이다. `AccountNotificationListener`의 핸들러들은 이미 **Level 1(본질적 멱등)**에 근접한다 — 이메일 재발송이 상태를 망가뜨리지 않는다(단, 중복 이메일 발송 자체는 발생한다). 완전한 멱등성을 원하면 Level 2(Ledger)를 적용한다.

```kotlin
// Level 2 — eventId를 키로 처리 여부 기록
fun handle(event: MoneyDepositedEvent, eventId: String) {
    if (sentEmailJpaRepository.existsByEventId(eventId)) return   // 이미 처리됨 — skip
    notificationService.sendEmail(/* ... */)
}
```

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 현재 방식(`domainEvents` + `pullDomainEvents()`)은 root와 일치 — 유지한다.
- **발행 메커니즘은 Outbox로 교체해야 한다**: `ApplicationEventPublisher` 동기 발행 → Repository의 Outbox 저장 → Relay → 메시지 큐 → Consumer.
- **`sealed interface`로 이벤트 타입을 묶으면** `when` 완전성 검사로 새 이벤트 처리 누락을 컴파일 타임에 방지한다.
- **EventHandler는 멱등하게 구현한다** — at-least-once 전달을 전제한다.

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [scheduling.md](scheduling.md) — `@Scheduled` 기반 Relay/Consumer 폴링
