# CQRS 패턴 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md) 참조.

## 현재 적용 수준 — 기본 아키텍처(Service 분리)

root 문서가 말하는 두 단계 중, 이 저장소의 `examples/`는 **기본 아키텍처**(Command Service / Query Service 분리)만 구현한다. root가 상세히 다루는 **Handler 기반 CQRS**(CommandBus/QueryBus, 독립 Handler 클래스)는 아직 없다 — 아래에서 둘 다 다룬다.

```
account/application/
  command/
    CreateAccountService.kt   ← @Service — 쓰기
    DepositService.kt
    WithdrawService.kt
    SuspendAccountService.kt
    ReactivateAccountService.kt
    CloseAccountService.kt
  query/
    GetAccountService.kt      ← @Service(readOnly) — 읽기
    GetTransactionsService.kt
```

각 Command Service는 유스케이스 하나당 클래스 하나다 (NestJS의 CommandHandler와 동일한 세분화 수준). `CreateAccountService.create()`처럼 메서드 하나만 갖는 것이 Kotlin/Spring 관용이다 — Java였다면 `OrderService.create()/cancel()/...`처럼 유스케이스를 한 클래스에 모으는 선택지도 있지만, 이 저장소는 root의 "유스케이스 단위로 분리"를 그대로 따른다.

```kotlin
// application/command/CreateAccountService.kt — 실제 코드
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)   // @Transactional — Account 저장 + Outbox 적재, 한 트랜잭션
        return CreateAccountResult(/* ... */)
        // 여기서 끝난다 — Outbox 드레인(OutboxPoller/OutboxConsumer)은 별도 컴포넌트가 독립적으로
        // 수행한다. domain-events.md 참고
    }
}
```

```kotlin
// application/query/GetAccountService.kt — 실제 코드
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) {
    fun getAccount(accountId: String, requesterId: String): GetAccountResult {
        val account = accountQuery.findByAccountIdAndOwnerId(accountId, requesterId)
            ?: throw AccountNotFoundException(accountId)
        return GetAccountResult(/* ... */)
    }
}
```

`@Transactional(readOnly = true)`는 Query Service에서만 붙인다 — Hibernate가 dirty checking을 생략하고 읽기 전용 커넥션을 사용해 최적화한다. Kotlin 문법 자체는 Command/Query 어느 쪽이든 동일하며, Query Service는 `AccountRepository`(쓰기 모델)가 아니라 별도의 읽기 전용 `AccountQuery` 인터페이스에 의존한다(아래 참고).

---

## 별도 Query 인터페이스 — `AccountQuery`

root(및 NestJS 구현)는 Query Service가 Repository가 아닌 별도의 읽기 전용 `<Domain>Query` 인터페이스를 사용하도록 명시한다 — Query Service가 `saveAccount`/`deleteAccount` 같은 쓰기 메서드에 접근하지 못하도록 컴파일 타임에 강제하기 위해서다. 이 인터페이스는 root 컨벤션대로 `application/query/`에 `AccountQuery`라는 이름으로 둔다(쓰기용 `AccountRepository`는 `domain/`에 그대로 유지).

```kotlin
// application/query/AccountQuery.kt — 실제 코드
interface AccountQuery {
    fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account?
    fun findTransactions(accountId: String, page: Int, take: Int): List<Transaction>
    fun countTransactions(accountId: String): Long
}
```

`GetAccountService`/`GetTransactionsService`는 `AccountRepository`가 아니라 `AccountQuery`를 생성자로 주입받는다. 구현체는 하나뿐이다 — `AccountRepositoryImpl`이 두 인터페이스를 모두 구현한다.

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드
@Repository
class AccountRepositoryImpl(/* ... */) : AccountRepository, AccountQuery {
    // findAccounts/saveAccount/deleteAccount/findTransactions(query)는 AccountRepository(쓰기 모델) 구현이고,
    // findByAccountIdAndOwnerId/findTransactions(accountId, page, take)/countTransactions는 AccountQuery(읽기 전용)
    // 구현이다 — 두 인터페이스의 조회 메서드 시그니처가 서로 다르므로(repository-pattern.md 참고)
    // 오버로드로 나뉘어 있을 뿐, 하나의 구현체가 두 인터페이스를 모두 만족시킨다는 점은 동일하다.
}
```

Command Service(`CreateAccountService` 등)는 여전히 `AccountRepository`(쓰기 모델, `saveAccount`/`deleteAccount` 포함)를 주입받는다 — 물리적으로는 같은 구현체·같은 테이블이지만, 인터페이스 분리로 각 Service가 자신에게 필요한 메서드에만 접근할 수 있다. Aggregate 복원 없이 프로젝션 전용 쿼리를 실행하는 수준의 완전한 CQRS(별도 읽기 모델/저장소)까지는 아니지만, root가 요구하는 "Query Service는 Repository가 아닌 읽기 전용 인터페이스에 의존"이라는 핵심 원칙은 충족한다.

---

## 언제 Handler 기반 CQRS로 전환하는가

| 상황 | 권장 |
|---|---|
| 유스케이스가 많아 Service 클래스가 계속 늘어날 때 | 현재처럼 유스케이스당 Service 클래스 유지 — 이미 Handler 수준으로 세분화됨 |
| Command/Query를 완전히 다른 저장소로 분리할 때 (CQRS 프로젝션 DB 등) | Query 전용 인터페이스 + 구현체 도입 |
| 이벤트 소싱, Saga 등 복잡한 워크플로가 필요할 때 | Axon Framework 등 전용 CQRS 프레임워크 검토 |

Spring은 NestJS의 `@nestjs/cqrs` 같은 CommandBus/QueryBus를 기본 제공하지 않는다. 대안:

1. **현재 방식 유지** — `@Service` 클래스를 Controller가 직접 호출. 유스케이스 수가 수십 개 수준일 때까지는 이것으로 충분하며, Kotlin의 간결한 클래스 선언(생성자 주입 한 줄) 덕분에 클래스 수가 늘어나는 비용이 Java보다 작다.
2. **Axon Framework** 도입 — `@CommandHandler`/`@QueryHandler` 애노테이션과 실제 CommandBus/QueryBus, Event Sourcing까지 제공한다. Kotlin과 100% 호환되지만 인프라(Axon Server 또는 내장 모드) 학습 비용이 있다.
3. **직접 구현** — `Map<KClass<*>, CommandHandler<*>>` 기반의 경량 CommandBus를 Kotlin으로 직접 작성. 프레임워크 의존을 늘리고 싶지 않을 때의 선택지.

이 저장소는 Account 도메인의 유스케이스 규모(6개 커맨드, 2개 쿼리)에서는 1번으로 충분하다고 판단해 Handler 기반 CQRS를 도입하지 않았다.

---

## Interface 레이어 — Controller가 Service 직접 호출

```kotlin
// interfaces/rest/AccountController.kt — 실제 코드
@PostMapping
@ResponseStatus(HttpStatus.CREATED)
fun createAccount(
    @RequestHeader("X-User-Id") requesterId: String,
    @Valid @RequestBody request: CreateAccountRequest,
): CreateAccountResult =
    createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
```

Command/Query Bus가 없으므로 Controller가 Service를 생성자로 주입받아 직접 호출한다. 에러 변환은 Controller가 아니라 전역 `@RestControllerAdvice`(`common/GlobalExceptionHandler.kt`, → [error-handling.md](error-handling.md))가 담당한다.

---

## Domain Event와의 경계

Aggregate가 수집한 이벤트는 Command Service가 아니라 Repository의 `save()`가 꺼내 Outbox 테이블에 저장한다 — Command Service는 저장 직후 `outboxRelay.processPending()`만 호출한다. root가 규정하는 Outbox 기반 발행을 그대로 구현한 것이다 — 상세는 [domain-events.md](domain-events.md) 참조.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, Command/Query Service 역할
- [domain-events.md](domain-events.md) — 이벤트 발행 메커니즘, Outbox 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계
