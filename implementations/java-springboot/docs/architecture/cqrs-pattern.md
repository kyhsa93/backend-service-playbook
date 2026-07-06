# CQRS 패턴 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md) 참고. 이 저장소는 Handler/Bus 기반 CQRS(`CommandBus`/`QueryBus`)까지는 도입하지 않고, [layer-architecture.md](layer-architecture.md)의 **경량 CQRS**(Command Service / Query Service 클래스 분리)만 적용한다 — 유스케이스 수가 적어 Handler 기반의 추가 인프라(Bus, Handler 레지스트리)가 아직 정당화되지 않는 규모이기 때문이다.

## 클래스 분리 — 현재 구현은 올바르다

`account/application/command/`와 `account/application/query/` 패키지로 쓰기/읽기 유스케이스를 분리하고, 각각 다른 트랜잭션 속성을 선언한다.

```java
// application/command/CreateAccountService.java — 쓰기
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
        return new CreateAccountResult(...);
    }
}
```

```java
// application/query/GetAccountService.java — 읽기
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountRepository accountRepository;

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountRepository.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        return new GetAccountResult(...);
    }
}
```

`@Transactional(readOnly = true)`는 Hibernate가 dirty checking을 건너뛰게 하여 읽기 전용 트랜잭션의 오버헤드를 줄인다 — Spring이 CQRS의 "쓰기/읽기 책임 분리"를 트랜잭션 속성 수준에서 문서화해주는 셈이다.

Command Service가 Aggregate에 로직을 위임(`Account.create()`)하고 스스로 비즈니스 규칙을 수행하지 않는 것도 루트 원칙과 일치한다.

---

## 알려진 gap — Query Service가 Repository를 직접 사용

루트 원칙: Query Service는 **쓰기용 Repository가 아니라 별도의 Query 인터페이스**를 사용해야 한다. Aggregate 복원 없이 읽기에 최적화된 쿼리를 실행하기 위함이다.

현재 `GetAccountService`/`GetTransactionsService`는 둘 다 쓰기용 `AccountRepository`를 그대로 주입받는다:

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountRepository accountRepository;   // ← 쓰기 인터페이스를 읽기에 사용
    ...
}
```

`AccountRepository`는 `save(Account)`를 갖는 쓰기 인터페이스이므로, 읽기 유스케이스가 이를 직접 참조하는 것은 [layer-architecture.md](layer-architecture.md)/cqrs-pattern.md가 명시한 "Repository는 Command Service에서만, Query 인터페이스는 Query Service에서만" 규칙 위반이다. `harness.sh`의 `package-structure` 검사는 `application/query` 디렉토리의 존재 여부만 확인하므로 이 위반을 잡아내지 못한다(`docs/implementations/java-springboot.md` 참고).

### 올바른 패턴 — `AccountQuery` 인터페이스 도입

```java
// application/query/AccountQuery.java — Query 인터페이스 (domain 레이어의 Repository와 별개)
public interface AccountQuery {
    Optional<GetAccountResult> getAccount(String accountId, String ownerId);
    List<TransactionSummary> getTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}
```

```java
// infrastructure/persistence/AccountQueryImpl.java — 구현체, 읽기 전용 쿼리 직접 작성
@Repository
@RequiredArgsConstructor
public class AccountQueryImpl implements AccountQuery {

    private final EntityManager em;

    @Override
    public Optional<GetAccountResult> getAccount(String accountId, String ownerId) {
        // Aggregate 복원 없이 응답 스키마에 맞춘 projection 쿼리 — Account 전체를 로딩하지 않는다
        String jpql = """
                SELECT new com.example.accountservice.account.application.query.GetAccountResult(
                    a.accountId, a.ownerId, a.email,
                    new com.example.accountservice.account.application.query.GetAccountResult$MoneyResult(a.balance.amount, a.balance.currency),
                    a.status, a.createdAt, a.updatedAt)
                FROM Account a
                WHERE a.accountId = :accountId AND a.ownerId = :ownerId AND a.deletedAt IS NULL
                """;
        return em.createQuery(jpql, GetAccountResult.class)
                .setParameter("accountId", accountId)
                .setParameter("ownerId", ownerId)
                .getResultStream().findFirst();
    }
    // getTransactions/countTransactions도 동일하게 projection 쿼리로 구현
}
```

```java
// application/query/GetAccountService.java — 수정된 버전
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {

    private final AccountQuery accountQuery;   // ← Query 인터페이스로 교체

    public GetAccountResult getAccount(String accountId, String requesterId) {
        return accountQuery.getAccount(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
    }
}
```

DI 바인딩은 Spring이 인터페이스-구현체를 자동 매칭한다 (`AccountQuery` 타입 주입 지점에 `@Repository`가 붙은 유일한 구현체 `AccountQueryImpl`이 연결됨). 여러 구현체가 필요해지면 `@Qualifier`로 명시한다.

**이렇게 분리하는 이유:**
- Query 쪽에서 JPA 엔티티 전체(`Account`, 연관된 `Money` 임베더블 등)를 로딩하지 않고 필요한 컬럼만 projection할 수 있어 성능상 이득이 크다.
- `AccountRepository`(쓰기)에 변경이 생겨도(예: 저장 방식 변경) Query 경로는 영향받지 않는다 — 결합도 감소.
- Query Service를 mock 테스트할 때 쓰기 메서드(`save`)가 없는 좁은 인터페이스만 mock하면 되어 [testing.md](testing.md)의 Application 단위 테스트가 더 명확해진다.

이 저장소는 현재 이 리팩터링 이전 상태이며, `examples/` 코드 변경은 이번 문서화 패스의 범위 밖이다 (후속 이슈로 트래킹).

---

## Command와 Query 객체

```java
// application/command/DepositCommand.java
public record DepositCommand(String accountId, String requesterId, long amount) {}

// application/query/GetTransactionsResult.java — Result는 API 응답 스키마
public record GetTransactionsResult(List<TransactionSummary> transactions, long count) {
    public record TransactionSummary(String transactionId, String type, MoneyResult amount, LocalDateTime createdAt) {}
    public record MoneyResult(long amount, String currency) {}
}
```

Command/Result 객체를 Java `record`로 표현하면 불변성과 `equals`/`hashCode`가 공짜로 생긴다. `interfaces/rest/DepositRequest.java` 같은 Interface DTO도 record로 정의하고 Controller에서 Command로 변환한다.

---

## EventHandler — Outbox 경유로 구현됨

CQRS의 `event/` 패키지 역할은 이 저장소에서 `account/application/event/`의 `*EventHandler`(예: `AccountCreatedEventHandler`)가 맡는다. 이들은 `@EventListener`가 아니라 `outbox/OutboxEventHandler` 인터페이스를 구현하며, `OutboxRelay`가 Outbox 테이블에서 읽은 이벤트를 라우팅해 호출한다. 상세 경로(Repository의 Outbox 저장, Command Service의 `processPending()` 호출 시점)는 [domain-events.md](domain-events.md)를 참고한다.

---

## 기본 아키텍처(Service 분리) vs Handler 기반 CQRS

| | 이 저장소 (경량 CQRS) | Handler 기반 CQRS (미적용) |
|---|---|---|
| 쓰기 진입점 | `XxxCommandService.method()` | `CommandHandler.execute()` |
| 읽기 진입점 | `XxxQueryService.method()` (수정 후: `AccountQuery` 사용) | `QueryHandler.execute()` |
| 라우팅 | Controller가 Service 직접 호출 | CommandBus/QueryBus |
| 유스케이스 단위 | Service의 메서드 | Handler 클래스 1개 |
| 적합 규모 | 현재 Account 도메인 (유스케이스 6개) | 유스케이스가 많아 Service가 비대해질 때 |

유스케이스가 늘어나 `AccountController`가 지나치게 많은 Service를 주입받게 되면, `application/command/*CommandHandler.java` + Spring이 관리하는 `Map<Class<?>, CommandHandler<?>>` 레지스트리 형태로 전환하는 것을 고려한다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Command/Query Service 기본 구조
- [domain-events.md](domain-events.md) — EventHandler와 Outbox 패턴
- [repository-pattern.md](repository-pattern.md) — Repository 패턴 (쓰기 전용)
- [testing.md](testing.md) — Command/Query Service의 Application 단위 테스트
