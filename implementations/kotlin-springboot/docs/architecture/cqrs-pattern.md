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
@Transactional
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.save(account)
        account.pullDomainEvents().forEach(eventPublisher::publishEvent)
        return CreateAccountResult(/* ... */)
    }
}
```

```kotlin
// application/query/GetAccountService.kt — 실제 코드
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountRepository: AccountRepository) {
    fun getAccount(accountId: String, requesterId: String): GetAccountResult {
        val account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
            ?: throw AccountNotFoundException(accountId)
        return GetAccountResult(/* ... */)
    }
}
```

`@Transactional(readOnly = true)`는 Query Service에서만 붙인다 — Hibernate가 dirty checking을 생략하고 읽기 전용 커넥션을 사용해 최적화한다. Kotlin 문법 자체는 Command/Query 어느 쪽이든 동일하며, `readOnly` 플래그 하나로 root의 "Repository는 Command에서만, Query 인터페이스는 Query에서만"이라는 구분을 대체로 표현한다 — 단, 이 예제는 별도의 읽기 전용 `AccountQuery` 인터페이스를 두지 않고 Query Service도 `AccountRepository`를 그대로 사용한다(아래 참고).

---

## 알려진 차이 — 별도 Query 인터페이스 부재

root(및 NestJS 구현)는 Query Service가 Repository가 아닌 별도의 읽기 전용 `<Domain>Query` 인터페이스를 사용하도록 명시한다 — Aggregate 복원 없이 읽기 최적화 쿼리를 실행하기 위해서다. 이 저장소의 `GetAccountService`/`GetTransactionsService`는 `AccountRepository`를 그대로 재사용한다. Account 도메인처럼 읽기 모델이 Aggregate와 거의 동일하고 조회량이 많지 않은 경우 실용적인 단순화이지만, 읽기 전용 프로젝션이 필요해지면 `application/query/AccountQuery.kt`(interface) + `infrastructure/persistence/AccountQueryImpl.kt`로 분리하는 것이 root 원칙에 맞다.

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

Command/Query Bus가 없으므로 Controller가 Service를 생성자로 주입받아 직접 호출한다. 에러 변환은 `@ExceptionHandler`(→ [error-handling.md](error-handling.md))가 담당한다.

---

## Domain Event와의 경계

Command Service는 Aggregate에서 이벤트를 꺼내(`pullDomainEvents()`) 발행까지 책임진다. 현재는 `ApplicationEventPublisher.publishEvent()`로 동기 in-process 발행을 하며, 이는 root가 규정하는 Outbox 기반 비동기 발행과 다르다 — 상세와 올바른 패턴은 [domain-events.md](domain-events.md) 참조.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, Command/Query Service 역할
- [domain-events.md](domain-events.md) — 이벤트 발행 메커니즘, Outbox 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 메서드 설계
