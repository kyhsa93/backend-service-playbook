# 레이어 아키텍처 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [layer-architecture.md](../../../../docs/architecture/layer-architecture.md) 참고.

## 의존 방향

```
interfaces/rest (@RestController)  →  application/{command,query} (@Service)  →  domain (Account, AccountRepository interface)
                                                                                        ↑
                                                                      infrastructure/persistence (@Repository, AccountRepositoryImpl)
```

- 상위 레이어는 하위 레이어에 의존할 수 있지만 역방향은 금지된다.
- `AccountRepositoryImpl`(infrastructure)이 `AccountRepository`(domain)를 구현해 의존성을 역전시킨다.
- Spring은 `AccountRepository` 타입으로 주입 지점을 선언하면, 클래스패스에서 이를 구현하는 유일한 `@Repository` 빈(`AccountRepositoryImpl`)을 찾아 자동 바인딩한다 — 별도의 DI 설정(모듈 provider 등록 등)이 필요 없다.

---

## Domain 레이어 — 알려진 gap: JPA 엔티티가 도메인을 겸함

루트 원칙: Domain 레이어는 **어떤 프레임워크에도 의존하지 않는 순수한 코드**로 작성한다 (ORM 포함).

`account/domain/Account.java`의 실제 코드:

```java
package com.example.accountservice.account.domain;

import jakarta.persistence.*;   // ← Domain 레이어가 JPA(ORM)에 의존

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String accountId;

    @Embedded
    private Money balance;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;
    // ...
}
```

`Account`, `Transaction`(하위 Entity), `Money`(`@Embeddable` record)까지 domain 패키지의 모든 클래스가 JPA 애노테이션을 직접 갖는다. **이것은 root의 "Domain은 프레임워크/ORM 무의존" 규칙을 위반한다** — 이 저장소의 알려진 아키텍처적 긴장(known tension)이며, root가 권장하는 패턴인 척 하지 않고 여기서 트레이드오프를 명확히 설명한다.

### 왜 이렇게 되어 있는가 — 트레이드오프

**장점 (현재 방식 — JPA 엔티티 겸용 도메인):**
- Aggregate 하나가 영속성 매핑까지 겸하므로 별도의 매핑 계층(도메인 객체 ↔ JPA 엔티티 변환 코드)이 필요 없다. 소규모 도메인에서는 보일러플레이트가 크게 줄어든다.
- Hibernate의 dirty checking, 지연 로딩(`@ElementCollection`, `@Embedded`) 등 JPA의 편의 기능을 Aggregate 자체가 그대로 활용한다.

**단점 (root가 순수 도메인을 요구하는 이유):**
- Domain 클래스가 `jakarta.persistence.*`를 import하는 순간, JPA 없이는 Aggregate를 테스트하거나 재사용할 수 없다 — 실제로는 문제되지 않는다(JPA 애노테이션은 런타임에 별도 의존성을 요구하지 않고 메타데이터로만 작동하므로 `new Account()` 형태의 순수 단위 테스트는 여전히 가능하다, [testing.md](testing.md) 참고). 진짜 리스크는 **설계 결합**이다 — `@Id`용 대리키(`Long id`)를 두거나 `protected Account() {}`(JPA가 요구하는 기본 생성자)를 열어두는 등, 영속성 프레임워크의 제약이 도메인 모델의 설계를 침범한다.
- 향후 영속성 기술을 교체(JPA → JOOQ, 다른 DB 등)해야 하면 Domain 클래스 자체를 건드려야 한다. 순수 도메인이었다면 `infrastructure/` 레이어의 매핑 코드만 바꾸면 됐을 것이다.
- Aggregate의 불변식을 지키는 메서드(`deposit()`, `withdraw()` 등)와 영속성 관심사(컬럼 매핑)가 한 클래스에 섞여, 파일이 커지고 "이 필드는 비즈니스 규칙용인가 DB 스키마용인가"를 매번 구분해야 한다.

### 올바른 패턴 — 분리했을 경우

```java
// domain/Account.java — 순수 도메인, 어떤 프레임워크도 import하지 않음
public class Account {
    private final String accountId;
    private final String ownerId;
    private Money balance;
    private AccountStatus status;
    // ... 도메인 메서드만
}
```

```java
// infrastructure/persistence/AccountEntity.java — JPA 매핑 전용
@Entity
@Table(name = "accounts")
class AccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String accountId;
    @Embedded
    private MoneyEmbeddable balance;
    // ...

    Account toDomain() { /* AccountEntity -> Account 변환 */ }
    static AccountEntity fromDomain(Account account) { /* Account -> AccountEntity 변환 */ }
}
```

```java
// infrastructure/persistence/AccountRepositoryImpl.java
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
                .map(AccountEntity::toDomain);   // 매핑은 여기서만
    }

    @Override
    public void save(Account account) {
        jpaRepository.save(AccountEntity.fromDomain(account));
    }
}
```

이 분리는 `AccountEntity`/`toDomain()`/`fromDomain()`이라는 추가 클래스와 변환 코드를 요구한다. **이 저장소는 현재 이 분리 이전 상태이며, 규모(Aggregate 6개 메서드, 필드 8개)를 고려하면 즉각적인 리팩토링이 강제되지는 않는다** — 다만 Aggregate가 복잡해지거나(다형성, 여러 하위 Entity 컬렉션), 영속성 기술 교체 가능성이 커지면 이 분리를 도입해야 한다.

---

## Application 레이어 — Command/Query Service

`application/command/`와 `application/query/`로 쓰기/읽기를 분리한다. 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참고.

```java
// application/command/CreateAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAccountService {
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.save(account);
        account.pullDomainEvents().forEach(eventPublisher::publishEvent);
        return new CreateAccountResult(/* ... */);
    }
}
```

- **생성자 주입 — `@Autowired` 불필요**: Lombok `@RequiredArgsConstructor`가 `final` 필드를 받는 생성자를 생성하고, Spring 4.3+는 생성자가 하나뿐인 클래스에 `@Autowired`를 생략해도 자동으로 주입한다.
- **비즈니스 로직은 Aggregate에 위임**: `CreateAccountService`는 `Account.create()`를 호출할 뿐 잔액 계산이나 상태 검증을 직접 수행하지 않는다.

Query Service는 `@Transactional(readOnly = true)`로 구분한다 — Hibernate가 dirty checking과 flush를 생략해 읽기 오버헤드를 줄인다.

```java
// application/query/GetAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountRepository accountRepository;   // 알려진 gap — cqrs-pattern.md 참고

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        return new GetAccountResult(/* ... */);
    }
}
```

Query Service가 쓰기용 `AccountRepository`를 그대로 사용하는 것은 root의 "Query 인터페이스로 분리" 원칙 위반이다 — 상세와 올바른 패턴은 [cqrs-pattern.md](cqrs-pattern.md) 참고.

---

## Infrastructure 레이어

`AccountRepositoryImpl`이 Domain의 `AccountRepository` 인터페이스를 구현한다. `EntityManager`(동적 JPQL)와 `AccountJpaRepository`(Spring Data 파생 쿼리)를 함께 사용하는 것도 이 레이어에서만 허용된다 — Domain/Application은 JPA API를 알지 못한다(단, 위에서 설명한 gap으로 `Account` 자체는 JPA 애노테이션을 갖는다).

```java
// infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드 (일부)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public List<Account> findAll(AccountFindQuery query) {
        String jpql = buildJpql(query, false);   // 동적 조건 조립, repository-pattern.md 참고
        var q = em.createQuery(jpql, Account.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        return q.getResultList();
    }
}
```

트랜잭션 전파는 root의 수동 `AsyncLocalStorage`/`ThreadLocal` 패턴 대신 Spring 선언적 `@Transactional`이 대체한다 — 상세는 [persistence.md](persistence.md) 참고.

---

## Interfaces 레이어

`interfaces/rest/AccountController`가 외부 HTTP 요청의 진입점이다. 요청을 Command/Query 객체로 변환해 Application Service에 위임하고, `@ExceptionHandler`로 에러를 HTTP 응답으로 변환한다.

```java
// interfaces/rest/AccountController.java — 실제 코드 (일부)
@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
public class AccountController {
    private final CreateAccountService createAccountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateAccountResult createAccount(
            @RequestHeader("X-User-Id") String requesterId,
            @Valid @RequestBody CreateAccountRequest request
    ) {
        return createAccountService.create(new CreateAccountCommand(requesterId, request.email(), request.currency()));
    }

    @ExceptionHandler(AccountException.class)
    public ResponseEntity<ErrorResponse> handleAccountException(AccountException e) { /* ... */ }
}
```

`@RequestHeader("X-User-Id")`로 인증되지 않은 값을 신뢰하는 것은 알려진 gap이다 — 올바른 패턴은 [authentication.md](authentication.md) 참고.

### Interface DTO — Application 객체의 thin wrapper

root는 TypeScript `extends`로 이를 표현하지만, Java에서는 **record 필드를 그대로 옮겨 담는 한 줄 변환**으로 나타난다:

```java
// interfaces/rest/DepositRequest.java — 실제 코드
public record DepositRequest(long amount) {}

// Controller 메서드 한 줄이 매핑 지점
depositService.deposit(new DepositCommand(accountId, requesterId, request.amount()));
```

별도 매핑 라이브러리(MapStruct 등)를 도입할 필요 없이, 필드 수가 적을 때는 record 생성자 호출이 가장 명확하다.

---

## 원칙 요약

| 원칙 | 이 저장소에서의 표현 | 상태 |
|---|---|---|
| 상위 레이어만 하위 레이어에 의존 | 패키지 구조 + `harness.sh`의 `package-structure`/`domain-purity` 검사 | 준수 |
| Domain은 프레임워크/ORM 무의존 | — | **위반 (알려진 gap, 위 참고)** |
| Repository는 인터페이스/구현 분리 | `interface`(domain) + Spring이 자동 바인딩하는 `@Repository` 구현체(infrastructure) | 준수 |
| Query Service는 Query 인터페이스만 사용 | `AccountRepository`(쓰기용)를 그대로 사용 | **위반 (알려진 gap, cqrs-pattern.md 참고)** |
| DI | 생성자 주입, Lombok `@RequiredArgsConstructor` | 준수 |

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query 분리, Query 인터페이스 gap
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
- [persistence.md](persistence.md) — 트랜잭션 전파
