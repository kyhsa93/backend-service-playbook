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

## Domain 레이어 — 순수 도메인 + JPA 매핑 분리

루트 원칙: Domain 레이어는 **어떤 프레임워크에도 의존하지 않는 순수한 코드**로 작성한다 (ORM 포함).

`account/domain/Account.java`의 실제 코드는 `jakarta.persistence.*`를 전혀 import하지 않는다:

```java
// account/domain/Account.java — 실제 코드, 순수 도메인
public class Account {
    private String accountId;
    private String ownerId;
    private String email;
    private Money balance;
    private AccountStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    private Account() {}

    public static Account create(String ownerId, String email, String currency) { /* ... */ }

    // Repository 구현체가 영속 데이터로부터 복원할 때 사용 — 도메인 이벤트를 생성하지 않는다
    public static Account reconstitute(String accountId, String ownerId, String email, Money balance,
            AccountStatus status, LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime deletedAt) { /* ... */ }
    // ... 도메인 메서드(deposit/withdraw/suspend/reactivate/close/delete)만
}
```

`Account`, `Transaction`(하위 Entity), `Money`(Value Object)까지 domain 패키지의 모든 클래스가 JPA 애노테이션을 갖지 않는다. 영속성 매핑은 `infrastructure/persistence/`에 전담 클래스로 분리되어 있다:

```java
// infrastructure/persistence/AccountJpaEntity.java — JPA 매핑 전용, 실제 코드
@Entity
@Table(name = "accounts")
public class AccountJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private String accountId;
    @Embedded
    private MoneyEmbeddable balance;   // domain.Money의 JPA 매핑 전용 대응물
    @Enumerated(EnumType.STRING)
    private AccountStatus status;      // enum 자체는 프레임워크 무의존이라 그대로 재사용
    // ...
}
```

```java
// infrastructure/persistence/AccountMapper.java — 변환 전담, 실제 코드 (일부)
final class AccountMapper {
    static Account toDomain(AccountJpaEntity entity) {
        return Account.reconstitute(entity.getAccountId(), entity.getOwnerId(), entity.getEmail(),
                entity.getBalance().toDomain(), entity.getStatus(),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getDeletedAt());
    }

    static AccountJpaEntity toNewEntity(Account account) { /* insert 대상 — PK 없음 */ }
    static AccountJpaEntity updateEntity(AccountJpaEntity entity, Account account) { /* update 대상 — PK 보존 */ }
}
```

```java
// infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드 (일부)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    private final AccountJpaRepository jpaRepository;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        // EntityManager로 조립한 JPQL 실행 후 AccountMapper::toDomain으로 매핑 — 매핑은 여기서만
    }

    @Override
    @Transactional
    public void saveAccount(Account account) {
        AccountJpaEntity entity = jpaRepository.findByAccountId(account.getAccountId())
                .map(existing -> AccountMapper.updateEntity(existing, account))   // 기존 row는 PK 보존하며 갱신
                .orElseGet(() -> AccountMapper.toNewEntity(account));             // 신규는 PK 없이 insert
        jpaRepository.save(entity);
    }
}
```

**PK(대리키) 처리**: 순수 도메인 `Account`는 숫자 PK(옛 `Long id`)를 전혀 갖지 않는다 — `accountId`(비즈니스 키)만 안다. `save()`가 기존 row를 찾을 때 `accountId`로 조회해 PK를 가진 기존 `AccountJpaEntity`를 얻고, 그 위에 `updateEntity()`로 최신 상태만 덮어써 PK를 보존한다. 신규 Account는 PK 없는 엔티티를 만들어 insert한다.

`Transaction`(하위 Entity)도 동일한 패턴으로 분리되어 있다 — `TransactionJpaEntity` + `TransactionMapper`. `Transaction`은 생성 후 변경되지 않으므로 insert 전용 변환(`toNewEntity`)만 존재한다.

이 분리는 `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable`/`AccountMapper`/`TransactionMapper`라는 추가 클래스와 변환 코드를 요구하지만, Domain 레이어가 어떤 프레임워크도 import하지 않게 되어 root 원칙과 완전히 일치한다.

---

## Application 레이어 — Command/Query Service

`application/command/`와 `application/query/`로 쓰기/읽기를 분리한다. 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참고.

```java
// application/command/CreateAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
public class CreateAccountService {
    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.saveAccount(account);      // @Transactional — Account 저장 + Outbox 적재, 한 트랜잭션(persistence.md 참고)
        outboxRelay.processPending();          // 커밋 직후 동기적으로 Outbox 드레인 — domain-events.md 참고
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
    private final AccountQuery accountQuery;   // 쓰기용 AccountRepository가 아닌 좁은 읽기 전용 인터페이스

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountQuery
                .findAccounts(new AccountFindQuery(0, 1, accountId, requesterId, null))
                .accounts().stream().findFirst()
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        return new GetAccountResult(/* ... */);
    }
}
```

`GetAccountService`는 `saveAccount`/`delete`가 없는 `AccountQuery`(application/query, `findAccounts`/`findTransactions`만 선언)에 의존한다 — `AccountRepositoryImpl`이 `AccountRepository`(domain, 쓰기)와 `AccountQuery`(application, 읽기)를 모두 구현하고, Spring이 각 주입 지점(`AccountRepository` 타입 vs `AccountQuery` 타입)에 같은 빈을 인터페이스별로 바인딩한다. 상세는 [cqrs-pattern.md](cqrs-pattern.md) 참고. `GetTransactionsService`도 동일하게 `AccountQuery`에 의존한다.

---

## Infrastructure 레이어

`AccountRepositoryImpl`이 Domain의 `AccountRepository` 인터페이스와 Application의 `AccountQuery` 인터페이스를 함께 구현한다. `EntityManager`(동적 JPQL)와 `AccountJpaRepository`(Spring Data 파생 쿼리)를 함께 사용하는 것도 이 레이어에서만 허용된다 — Domain/Application은 JPA API를 알지 못한다. JPQL은 순수 도메인 `Account`가 아니라 `AccountJpaEntity`(JPA 매핑 전용)를 대상으로 하고, 결과는 `AccountMapper`로 변환한다.

```java
// infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드 (일부)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQuery {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public AccountsWithCount findAccounts(AccountFindQuery query) {
        String jpql = buildJpql(query, false);   // 동적 조건 조립, repository-pattern.md 참고
        var q = em.createQuery(jpql, AccountJpaEntity.class)
                .setFirstResult(query.page() * query.take())
                .setMaxResults(query.take());
        applyParams(q, query);
        List<Account> accounts = q.getResultList().stream().map(AccountMapper::toDomain).toList();   // JPA 엔티티 -> 순수 도메인
        long count = /* 동일 조건의 COUNT 쿼리 */ 0;
        return new AccountsWithCount(accounts, count);
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
| Domain은 프레임워크/ORM 무의존 | `Account`/`Transaction`/`Money`는 순수 도메인, JPA 매핑은 `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable`(infrastructure)로 분리 | 준수 |
| Repository는 인터페이스/구현 분리 | `interface`(domain) + Spring이 자동 바인딩하는 `@Repository` 구현체(infrastructure) | 준수 |
| Query Service는 Query 인터페이스만 사용 | `GetAccountService`/`GetTransactionsService` 모두 `AccountQuery`(읽기 전용) 사용 | 준수 |
| DI | 생성자 주입, Lombok `@RequiredArgsConstructor` | 준수 |

---

### 관련 문서

- [tactical-ddd.md](tactical-ddd.md) — Aggregate, Entity, Value Object 상세
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 상세
- [cqrs-pattern.md](cqrs-pattern.md) — Command/Query 분리, Query 인터페이스
- [domain-events.md](domain-events.md) — Domain Event, Outbox 패턴
- [persistence.md](persistence.md) — 트랜잭션 전파
