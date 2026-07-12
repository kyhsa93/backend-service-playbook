# 레이어 아키텍처 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root layer-architecture.md](../../../../docs/architecture/layer-architecture.md) 참조.

## 의존 방향

```
interfaces/rest (@RestController)  →  application/{command,query} (@Service)  →  domain (Account, AccountRepository interface)
                                                                                        ↑
                                                                        infrastructure/persistence (@Repository, AccountRepositoryImpl)
```

`account/domain/`은 Spring 어노테이션도 JPA(`jakarta.persistence`) 애노테이션도 없는 순수 Kotlin으로 작성되고(JPA 매핑은 `infrastructure/persistence/`의 `AccountJpaEntity`/`MoneyEmbeddable` + Mapper가 전담, [directory-structure.md](directory-structure.md) 참고), `infrastructure/persistence/AccountRepositoryImpl`이 `domain/AccountRepository` 인터페이스를 구현해 의존성을 역전시킨다. harness의 `domain-purity`(도메인에 `@Service`/`@Component`/`@Repository`/`@Controller` 및 `jakarta.persistence` import 금지), `service-annotation`(`@Service`는 application/ 안에만), `repository-annotation`(`@Repository`는 infrastructure/ 안에만) 검사가 이 의존 방향을 실제로 강제한다.

---

## Domain 레이어

```kotlin
// domain/AccountRepository.kt — 실제 코드. Spring 무의존 순수 interface
interface AccountRepository {
    fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long>
    fun saveAccount(account: Account)
    fun deleteAccount(accountId: String)
    fun findTransactions(query: TransactionFindQuery): Pair<List<Transaction>, Long>
}
```

root는 Repository 인터페이스를 TypeScript `abstract class`로 표현하지만(NestJS DI가 인터페이스를 런타임 토큰으로 쓸 수 없어서), **Kotlin/Spring은 `interface` 자체가 DI 토큰**이 된다 — Spring이 클래스패스에서 `AccountRepository`를 구현하는 유일한 `@Repository` Bean(`AccountRepositoryImpl`)을 찾아 자동 바인딩한다. 별도의 `abstract class` 우회가 필요 없다.

Kotlin의 **null-safety**가 이 레이어에서 하는 역할이 root의 TypeScript 버전과 다르다: 전용 단건 조회 메서드를 두지 않고, `findAccounts(...)`가 반환한 `Pair`의 `List<Account>`에 `firstOrNull()`을 호출해 "찾지 못함"을 타입 시스템에 새긴다(repository-pattern.md 참고). 호출자는 `?:` 엘비스 연산자나 스마트 캐스트 없이는 컴파일이 안 되므로, null 체크를 빠뜨리는 것 자체가 불가능하다.

```kotlin
// application/command/DepositService.kt — 실제 코드
val (accounts, _) = accountRepository.findAccounts(
    AccountFindQuery(page = 0, take = 1, accountId = command.accountId, ownerId = command.requesterId),
)
val account = accounts.firstOrNull() ?: throw AccountNotFoundException(command.accountId)
// 이 라인 이후 account는 스마트 캐스트로 Account (non-null) 타입 — Optional.get()이나
// null 체크 없이 바로 account.email 등에 접근해도 컴파일러가 non-null임을 보장한다
```

Java/TypeScript였다면 `Optional<Account>`(Java) 또는 `Account | undefined`(TS) + 명시적 언래핑이 필요했던 지점이, Kotlin에서는 `?:` 한 줄과 스마트 캐스트로 끝난다 — **null-safety는 여기서 "도메인 불변식을 어기는 코드를 컴파일 단계에서 막는" 도구로 작동한다.** "계좌를 찾은 이후의 코드에서는 반드시 계좌가 존재한다"는 도메인 규칙이 타입으로 강제된다.

---

## Application 레이어 — Command/Query Service

```kotlin
// application/command/CreateAccountService.kt — 실제 코드
@Service
class CreateAccountService(
    private val accountRepository: AccountRepository,
    private val outboxRelay: OutboxRelay,
) {
    fun create(command: CreateAccountCommand): CreateAccountResult {
        val account = Account.create(command.requesterId, command.currency, command.email)
        accountRepository.saveAccount(account)      // @Transactional — Account 저장 + Outbox 적재, 한 트랜잭션
        outboxRelay.processPending()          // 커밋 직후 동기 드레인 — domain-events.md 참고
        return CreateAccountResult(/* ... */)
    }
}
```

- **생성자 주입 — `@Autowired` 불필요**: 주 생성자(primary constructor)의 파라미터가 그대로 DI 대상이다. Spring 4.3+는 생성자가 하나뿐이면 `@Autowired` 애노테이션조차 생략 가능하다.
- **`open` 필요성**: Kotlin 클래스는 기본 `final`이다. Spring AOP(`@Transactional` 프록시)는 클래스 상속 기반 프록시(CGLIB)를 만들어야 하므로, `@Service`/`@Repository` 클래스는 `open`이어야 한다. 이 저장소는 `build.gradle.kts`에 `kotlin("plugin.spring")`을 적용해 `@Component` 계열 애노테이션이 붙은 클래스를 컴파일러가 자동으로 `open` 처리한다 — 소스에 `open` 키워드를 직접 쓸 필요가 없다.
- **비즈니스 로직은 Aggregate에 위임**: `CreateAccountService`는 `Account.create()`를 호출할 뿐, 잔액 계산이나 상태 전이 규칙을 직접 구현하지 않는다.

Query Service는 `@Transactional(readOnly = true)`로 구분한다 — Hibernate가 dirty checking과 flush를 생략해 읽기 성능을 최적화한다.

```kotlin
// application/query/GetAccountService.kt — 실제 코드
@Service
@Transactional(readOnly = true)
class GetAccountService(private val accountQuery: AccountQuery) { /* ... */ }
```

Query Service는 쓰기 모델 `AccountRepository`가 아니라 읽기 전용 `AccountQuery`를 주입받는다 — 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참조.

Command/Query 세분화 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참조.

---

## Infrastructure 레이어

```kotlin
// infrastructure/persistence/AccountRepositoryImpl.kt — 실제 코드 (일부)
@Repository
class AccountRepositoryImpl(
    private val jpaRepository: AccountJpaRepository,
    private val transactionJpaRepository: TransactionJpaRepository,
    private val em: EntityManager,
) : AccountRepository, AccountQuery {

    override fun findAccounts(query: AccountFindQuery): Pair<List<Account>, Long> { /* ... */ }

    override fun saveAccount(account: Account) {
        jpaRepository.save(account)
        val pending = account.pullPendingTransactions()
        if (pending.isNotEmpty()) transactionJpaRepository.saveAll(pending)
    }

    // AccountQuery(읽기 전용 포트) — 시그니처가 달라 별도 오버로드로 구현
    override fun findByAccountIdAndOwnerId(accountId: String, ownerId: String): Account? =
        jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
}
```

`AccountRepositoryImpl`이 `AccountRepository`를 구현하고, Spring이 `interface → 구현체` 바인딩을 자동으로 수행한다(클래스패스에 구현체가 하나뿐이므로 `@Qualifier` 불필요). `EntityManager`(JPQL 동적 쿼리 조립), `AccountJpaRepository`(Spring Data 기본 CRUD)를 둘 다 사용하는 것도 이 레이어에서만 허용된다 — Domain/Application은 JPA API를 전혀 알지 못한다.

트랜잭션 전파는 root의 수동 `AsyncLocalStorage`/`ThreadLocal` 패턴 대신 Spring 선언적 `@Transactional`로 대체한다 — 상세는 [persistence.md](persistence.md) 참조.

---

## Interfaces 레이어

```kotlin
// interfaces/rest/AccountController.kt — 실제 코드 (일부)
@RestController
@RequestMapping("/accounts")
class AccountController(
    private val createAccountService: CreateAccountService,
    private val getAccountService: GetAccountService,
    // ...
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(
        @RequestHeader("X-User-Id") requesterId: String,
        @Valid @RequestBody request: CreateAccountRequest,
    ): CreateAccountResult =
        createAccountService.create(CreateAccountCommand(requesterId, request.currency, request.email))
}
```

root의 "Interface DTO = Application 객체의 thin wrapper"(TypeScript `extends`) 원칙은 Kotlin에서 **`data class` 생성자 호출**로 표현된다 — 상속으로 감싸는 대신 Request DTO의 필드를 그대로 Command의 생성자 인자로 매핑한다. `CreateAccountRequest`(interfaces/rest)와 `CreateAccountCommand`(application/command)는 별개의 `data class`이며, Controller 메서드 한 줄이 매핑 지점이다 — Kotlin의 named argument 덕분에 필드가 늘어나도 순서 실수 없이 매핑할 수 있다.

에러 변환(`@ExceptionHandler`)이 이 레이어의 책임이라는 점은 root와 동일 — 상세는 [error-handling.md](error-handling.md).

---

## 원칙 요약

| 원칙 | Kotlin/Spring에서의 표현 |
|---|---|
| 상위 레이어만 하위 레이어에 의존 | 패키지 구조 + harness 검사(`domain-purity` 등)로 강제 |
| Domain은 프레임워크 무의존 | Spring 어노테이션 금지 (JPA는 예외적 허용, [directory-structure.md](directory-structure.md) 참고) |
| Repository는 인터페이스/구현 분리 | `interface`(domain) + Spring이 자동 바인딩하는 구현체(infrastructure) |
| "찾지 못함"의 표현 | `Optional<T>` 대신 `T?` + `?:` — 컴파일러가 null 체크 누락을 막는다 |
| DI | 생성자 주입, `@Autowired` 불필요 |

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query Service 분리
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
