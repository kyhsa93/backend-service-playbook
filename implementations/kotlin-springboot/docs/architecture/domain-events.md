# 도메인 이벤트 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root domain-events.md](../../../../docs/architecture/domain-events.md) 참조.

## Outbox 패턴 — `examples/`에 실제로 구현되어 있다

root는 명확히 규정한다: *"Domain Event는 in-process 이벤트 버스를 사용하지 않는다. Outbox → 메시지 큐 → EventConsumer 경로로 전달된다."* 이 저장소의 `examples/`는 이 규칙을 따른다 — 다만 별도 메시지 큐(SQS 등)를 두는 대신, Command Service가 트랜잭션 커밋 직후 Relay를 동기 호출해 같은 프로세스 안에서 즉시 드레인하는 단순화된 형태다(아래 "메시지 큐를 두지 않은 이유" 참고).

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

## 1단계: Aggregate에서 이벤트 수집 (변경 없음)

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

`@Transient`로 표시해 JPA가 이 필드를 컬럼으로 매핑하지 않도록 한다 — 이벤트는 영속 상태가 아니라 "아직 전달되지 않은 사실의 목록"이다. 이벤트 타입은 여전히 서로 무관한 `data class`(`MutableList<Any>`)다 — `sealed interface`로 묶는 개선은 아직 적용하지 않았다(아래 "알려진 잔여 갭" 참고).

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
            runCatching { dispatch(row.eventType, row.payload) }
                .onSuccess { row.markProcessed() }
                .onFailure { logger.error("이벤트 처리 실패: eventType={}, eventId={}", row.eventType, row.eventId, it) }
        }
    }

    private fun dispatch(eventType: String, payload: String) {
        when (eventType) {
            "AccountCreatedEvent" -> accountCreatedEventHandler.handle(objectMapper.readValue(payload, AccountCreatedEvent::class.java))
            "MoneyDepositedEvent" -> moneyDepositedEventHandler.handle(objectMapper.readValue(payload, MoneyDepositedEvent::class.java))
            // ... 나머지 4개 이벤트 타입도 동일한 패턴
            else -> logger.warn("알 수 없는 이벤트 타입: {}", eventType)
        }
    }
}
```

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
    fun handle(event: AccountCreatedEvent) {
        notificationService.sendEmail(
            accountId = event.accountId,
            eventType = "AccountCreated",
            recipient = event.email,
            subject = "[Account] 계좌가 개설되었습니다",
            body = "계좌(${event.accountId})가 개설되었습니다. 통화: ${event.currency}",
        )
    }
}
```

Handler는 예외를 잡지 않는다 — 실패를 삼키지 않고 그대로 전파해, `OutboxRelay`가 해당 행을 미처리 상태로 남기고 다음 호출에서 재시도하게 한다.

harness의 `event-placement` 규칙은 `*EventHandler` 접미사를 가진 파일이 `application/event/` 패키지 안에 있는지 검사한다 — 6개 Handler 모두 이 규칙을 그대로 통과한다.

---

## 이벤트 핸들러 멱등성

Relay가 at-least-once로 재시도하므로, `NotificationService.sendEmail()`이 같은 이벤트에 대해 두 번 호출될 수 있다(예: SES 호출은 성공했지만 `markProcessed()` 커밋 전에 프로세스가 죽는 경우). 현재 Handler들은 이미 **Level 1(본질적 멱등)**에 근접한다 — 이메일 재발송이 상태를 망가뜨리지 않는다(단, 중복 이메일 발송 자체는 발생할 수 있다). 완전한 멱등성을 원하면 Level 2(Ledger)를 적용한다.

```kotlin
// Level 2 — eventId를 키로 처리 여부 기록 (아직 미적용)
fun handle(event: MoneyDepositedEvent, eventId: String) {
    if (sentEmailJpaRepository.existsByEventId(eventId)) return   // 이미 처리됨 — skip
    notificationService.sendEmail(/* ... */)
}
```

3단계 전략 상세는 [root domain-events.md — 이벤트 핸들러 멱등성](../../../../docs/architecture/domain-events.md#이벤트-핸들러-멱등성) 참조.

---

## 알려진 잔여 갭

- **`sealed interface`로 이벤트 타입 묶기**: `domainEvents: MutableList<Any>`는 여전히 `Any`로 타입을 지운다. `AccountDomainEvent` 같은 `sealed interface`로 묶으면 `when` 분기의 완전성을 컴파일러가 검사하게 만들 수 있다 — 이벤트 타입이 늘어나도 처리 누락을 컴파일 타임에 잡는다. `OutboxRelay.dispatch()`의 `eventType` 문자열 `when` 분기는 현재 컴파일 타임 완전성 검사가 없다(새 이벤트 타입 추가 시 `else` 분기로 조용히 빠질 위험).
- **Level 2 멱등성 미적용**: 위 "이벤트 핸들러 멱등성" 절 참고.

## 원칙 요약

- **Aggregate가 이벤트를 수집**하는 방식(`domainEvents` + `pullDomainEvents()`)은 그대로 유지한다.
- **발행 메커니즘은 Outbox 기반이다**: Repository가 Aggregate 저장과 같은 트랜잭션에 Outbox 행을 커밋하고, Command Service가 그 트랜잭션이 실제로 커밋된 직후 `OutboxRelay.processPending()`을 동기 호출해 테이블 전체를 드레인한다.
- **`@Scheduled` 폴러나 메시지 큐를 두지 않는다** — 단일 인스턴스 예제 규모에서는 커밋 직후 동기 드레인만으로 원자성과 at-least-once 전달이라는 핵심 보장을 얻을 수 있고, e2e 테스트에서도 결정적으로 동작한다.
- **EventHandler는 예외를 삼키지 않는다** — 실패 처리는 `OutboxRelay`가 중앙에서 담당하며, 실패한 이벤트는 다음 호출에서 재시도된다(at-least-once 전달을 전제로 Handler를 멱등하게 구현하는 것이 이상적이다).

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate의 이벤트 수집 책임
- [cqrs-pattern.md](cqrs-pattern.md) — Command Service와 이벤트 발행의 경계
- [repository-pattern.md](repository-pattern.md) — Repository에서 Outbox 저장
- [persistence.md](persistence.md) — `@Transactional` 경계를 Repository로 옮긴 이유
- [scheduling.md](scheduling.md) — `@Scheduled` 기반 Relay/Consumer로 확장하는 방법(메시지 큐 도입 시)
