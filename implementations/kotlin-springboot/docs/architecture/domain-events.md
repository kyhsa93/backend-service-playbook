# 도메인 이벤트 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root domain-events.md](../../../../docs/architecture/domain-events.md) 참조.

## Outbox 패턴 — `examples/`에 실제로 구현되어 있다

root는 명확히 규정한다: *"Domain Event는 in-process 이벤트 버스를 사용하지 않는다. Repository.save()가 Aggregate와 같은 트랜잭션으로 Outbox에 저장하고, Relay가 그 저장된 이벤트를 읽어 핸들러에 전달한다."* 이 저장소의 `examples/`는 이 규칙을 그대로 따른다 — root가 동등하게 인정하는 두 경로(같은 프로세스 안 동기 드레인 / 메시지 큐를 경유하는 분산 드레인) 중, Command Service가 트랜잭션 커밋 직후 Relay를 동기 호출해 같은 프로세스 안에서 즉시 드레인하는 전자를 택했다(아래 "메시지 큐를 두지 않은 이유" 참고).

```kotlin
// application/command/DepositService.kt — 현재 코드
@Service
class DepositService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {
    fun deposit(command: DepositCommand): TransactionResult {
        val (accounts, _) = accountRepository.findAccounts(
            AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
        )
        val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
        val transaction = account.deposit(command.amount)
        accountRepository.saveAccount(account)     // ← Aggregate + Outbox 행을 한 트랜잭션에 커밋
        outboxRelay.processPending()        // ← 커밋 후 동기적으로 미처리 이벤트 전체를 드레인
        return TransactionResult(/* ... */)
    }
}
```

Command Service는 `OutboxRelay`에만 의존한다 — `ApplicationEventPublisher`/`@EventListener`는 쓰지 않는다.

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
                this.eventType = event::class.simpleName ?: "Unknown"
                this.payload = objectMapper.writeValueAsString(event)
                this.createdAt = LocalDateTime.now()
            }
    }

    fun markProcessed() { processed = true }
}
```

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

이전 코드는 Command Service 클래스 전체(`@Service @Transactional class DepositService`)가 하나의 트랜잭션이었다. 지금은 그 애노테이션을 Command Service에서 제거하고 `AccountRepositoryImpl.saveAccount()`에 옮겼다 — 이렇게 하면 `accountRepository.saveAccount(account)` 호출이 자체 트랜잭션 경계가 되어, 이 메서드 호출이 반환하는 시점에 **실제로 커밋이 끝나 있다**. 그다음 줄의 `outboxRelay.processPending()`이 "트랜잭션 커밋 직후"를 문자 그대로 만족하는 이유다 — Spring AOP 프록시가 서로 다른 빈(Command Service → Repository) 사이의 호출을 가로채 트랜잭션 경계를 강제하기 때문에 별도의 `TransactionTemplate`이나 `TransactionSynchronizationManager` 없이도 성립한다.

---

## 3단계: OutboxRelay — 커밋 직후 동기 드레인 (구현됨, `@Scheduled` 아님)

```kotlin
// outbox/OutboxRelay.kt — 실제 코드
@Component
class OutboxRelay(
    private val outboxEventJpaRepository: OutboxEventJpaRepository,
    private val objectMapper: ObjectMapper,
    private val accountCreatedEventHandler: AccountCreatedEventHandler,
    private val moneyDepositedEventHandler: MoneyDepositedEventHandler,
    private val moneyWithdrawnEventHandler: MoneyWithdrawnEventHandler,
    private val accountSuspendedEventHandler: AccountSuspendedEventHandler,
    private val accountReactivatedEventHandler: AccountReactivatedEventHandler,
    private val accountClosedEventHandler: AccountClosedEventHandler,
) {
    @Transactional
    fun processPending() {
        val pending = outboxEventJpaRepository.findByProcessedFalseOrderByCreatedAtAsc()
        for (row in pending) {
            runCatching { dispatch(row.eventType, row.eventId, row.payload) }
                .onSuccess { row.markProcessed() }
                .onFailure { logger.error("이벤트 처리 실패: eventType={}, eventId={}", row.eventType, row.eventId, it) }
        }
    }

    private fun dispatch(eventType: String, eventId: String, payload: String) {
        when (eventType) {
            "AccountCreatedEvent" ->
                accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java), eventId)
            "MoneyDepositedEvent" ->
                moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java), eventId)
            // ... 나머지 4개 이벤트 타입도 동일한 패턴 — Outbox 행의 eventId를 Handler에 그대로 넘긴다
            else -> logger.warn("알 수 없는 이벤트 타입: {}", eventType)
        }
    }
}
```

`row.eventId`(Outbox 행의 고유 식별자)를 Handler까지 그대로 전달한다 — 이 값이 4단계에서 Level 2 멱등성의 중복 발송 방지 키가 된다.

`@Scheduled` 폴러가 아니다 — 6개 Command Service 각각이 `accountRepository.saveAccount()`가 반환한(=커밋된) 직후 `processPending()`을 동기 호출한다. **테이블 전체를 대상으로 드레인**하므로, 이번 커맨드가 남긴 이벤트뿐 아니라 이전 호출에서 실패해 `processed=false`로 남아 있던 이벤트도 이번 호출에서 함께 재시도된다 — 어떤 커맨드든 다음 호출 한 번이 곧 전체 재시도 트리거가 된다.

행 하나의 핸들러 실행이 실패해도(`runCatching`) 예외를 전파하지 않는다 — 로그만 남기고 해당 행은 `processed=false`로 남아 다음 호출에서 다시 시도된다(at-least-once 전달). 다른 행의 처리에는 영향을 주지 않는다.

이 방식은 실제 큐(SQS 등)를 두는 nestjs 구현과 트리거 전략은 동일하지만(같은 트랜잭션에 Outbox 저장 → 커밋 후 동기 드레인), 드레인 대상이 프로세스 내 메서드 호출이라는 점이 다르다 — 아래 참고.

### 메시지 큐를 두지 않은 이유

root 원칙이 요구하는 "at-least-once 전달 + Aggregate 저장과의 원자성"이라는 핵심 보장은 **Outbox 테이블 + 같은 트랜잭션 커밋**만으로 이미 확보된다. 메시지 큐(SQS 등)는 이 보장 위에 "다른 프로세스/인스턴스로 넘겨준다"는 추가 속성만 얹을 뿐이다. 이 저장소는 단일 인스턴스로 배포되는 예제이고, 결정적 동작(e2e 테스트에서 이메일 발송을 항상 즉시 관찰할 수 있어야 함)이 중요하므로, 큐를 생략하고 Relay를 동기 호출하는 쪽을 택했다. 여러 인스턴스로 스케일하거나 발신자를 물리적으로 분리해야 하는 시점이 오면 `OutboxRelay.processPending()` 내부를 SQS 발행으로 바꾸고 별도 Consumer 프로세스를 추가하면 된다 — Outbox 테이블과 트랜잭션 경계는 그대로 재사용된다.

---

## 4단계: `application/event/` Handler (구현됨)

메시지 큐가 없으므로 별도의 `EventConsumer`는 없다 — `OutboxRelay.dispatch()`가 직접 이벤트 타입별 Handler를 호출한다. 이벤트마다 클래스 하나, `NotificationService`(Technical Service) 호출 하나로 구성된다.

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

Handler는 예외를 잡지 않는다 — 실패를 삼키지 않고 그대로 전파해, `OutboxRelay`가 해당 행을 미처리 상태로 남기고 다음 호출에서 재시도하게 한다. `eventId`(Outbox 행의 `eventId`)는 그대로 `NotificationService.sendEmail()`에 `sourceEventId`로 전달되어 Level 2 멱등성의 키로 쓰인다.

harness의 `event-placement` 규칙은 `*EventHandler` 접미사를 가진 파일이 `application/event/` 패키지 안에 있는지 검사한다 — 6개 Handler 모두 이 규칙을 그대로 통과한다.

---

## 이벤트 핸들러 멱등성

Relay가 at-least-once로 재시도하므로, `NotificationService.sendEmail()`이 같은 이벤트에 대해 두 번 호출될 수 있다(예: SES 호출은 성공했지만 `markProcessed()` 커밋 전에 프로세스가 죽는 경우). Handler들은 **Level 1(본질적 멱등)**에도 근접한다 — 이메일 재발송이 상태를 망가뜨리지는 않는다. 여기에 더해 **Level 2(Ledger)**도 적용되어 있다 — `sent_emails` 테이블의 `source_event_id` 컬럼(unique 제약)이 Ledger 역할을 한다.

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

`sourceEventId`는 `OutboxRelay.dispatch()`가 넘겨주는 Outbox 행의 `eventId`다 — 새 테이블을 두지 않고, 이미 발송 이력을 기록하던 `SentEmail`에 컬럼 하나(`source_event_id`, unique)만 추가해 "이 Outbox 이벤트가 이미 이메일 발송으로 이어졌는가"를 확인한다. 6개 EventHandler 모두 `handle(event, eventId)`로 `eventId`를 전달받아 `NotificationService.sendEmail()`의 `sourceEventId`로 그대로 넘긴다.

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 방식(`domainEvents` + `pullDomainEvents()`)은 그대로 유지한다. 이벤트 타입은 공통 `sealed interface DomainEvent`를 구현한다 — `when` 분기의 완전성 검사를 컴파일러에 맡길 수 있다.
- **발행 메커니즘은 Outbox 기반이다**: Repository가 Aggregate 저장과 같은 트랜잭션에 Outbox 행을 커밋하고, Command Service가 그 트랜잭션이 실제로 커밋된 직후 `OutboxRelay.processPending()`을 동기 호출해 테이블 전체를 드레인한다.
- **`@Scheduled` 폴러나 메시지 큐를 두지 않는다** — 단일 인스턴스 예제 규모에서는 커밋 직후 동기 드레인만으로 원자성과 at-least-once 전달이라는 핵심 보장을 얻을 수 있고, e2e 테스트에서도 결정적으로 동작한다.
- **EventHandler는 예외를 삼키지 않는다** — 실패 처리는 `OutboxRelay`가 중앙에서 담당하며, 실패한 이벤트는 다음 호출에서 재시도된다(at-least-once 전달을 전제로 Handler를 멱등하게 구현하는 것이 이상적이다).

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [persistence.md](persistence.md) — `@Transactional` 경계를 Repository로 옮긴 이유
- [scheduling.md](scheduling.md) — `@Scheduled` 기반 Relay/Consumer로 확장하는 방법(메시지 큐 도입 시)
