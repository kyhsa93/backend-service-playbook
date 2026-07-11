# CQRS 패턴 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [cqrs-pattern.md](../../../../docs/architecture/cqrs-pattern.md) 참고. 이 저장소는 Handler/Bus 기반 CQRS(`CommandBus`/`QueryBus`)까지는 도입하지 않고, [layer-architecture.md](layer-architecture.md)의 **경량 CQRS**(Command Service / Query Service 클래스 분리)만 적용한다 — 유스케이스 수가 적어 Handler 기반의 추가 인프라(Bus, Handler 레지스트리)가 아직 정당화되지 않는 규모이기 때문이다.

## 클래스 분리 — 현재 구현은 올바르다

`account/application/command/`와 `account/application/query/` 패키지로 쓰기/읽기 유스케이스를 분리하고, 각각 다른 트랜잭션 속성을 선언한다.

```java
// application/command/CreateAccountService.java — 쓰기
@Service
@RequiredArgsConstructor
public class CreateAccountService {
    private final AccountRepository accountRepository;
    private final OutboxRelay outboxRelay;

    public CreateAccountResult create(CreateAccountCommand command) {
        Account account = Account.create(command.requesterId(), command.email(), command.currency());
        accountRepository.save(account);   // @Transactional — Account 저장 + Outbox 적재, 한 트랜잭션
        outboxRelay.processPending();       // 커밋 직후 동기 드레인 — domain-events.md 참고
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

## `GetAccountService` — `AccountQueryRepository` 인터페이스로 분리됨

루트 원칙: Query Service는 **쓰기용 Repository가 아니라 별도의 Query 인터페이스**를 사용해야 한다. Aggregate 복원 없이 읽기에 최적화된 쿼리를 실행하기 위함이다.

`GetAccountService`는 쓰기용 `AccountRepository`가 아니라 `AccountQueryRepository`(application/query)를 주입받는다:

```java
// application/query/AccountQueryRepository.java — Query 인터페이스, 실제 코드
public interface AccountQueryRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
}
```

```java
// application/query/GetAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAccountService {
    private final AccountQueryRepository accountQueryRepository;   // 좁은 읽기 전용 인터페이스

    public GetAccountResult getAccount(String accountId, String requesterId) {
        Account account = accountQueryRepository.findByAccountIdAndOwnerId(accountId, requesterId)
                .orElseThrow(() -> new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_FOUND, "계좌를 찾을 수 없습니다."));
        return new GetAccountResult(/* ... */);
    }
}
```

`AccountRepositoryImpl`(infrastructure)이 `AccountRepository`(domain, 쓰기)와 `AccountQueryRepository`(application, 읽기)를 **모두** 구현한다 — 별도의 Query 전용 구현 클래스를 새로 만들지 않고, 기존 구현체가 두 인터페이스의 교차점(`findByAccountIdAndOwnerId`)을 공유한다:

```java
// infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드 (일부)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository, AccountQueryRepository {
    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId)
                .map(AccountMapper::toDomain);
    }
    // ... AccountRepository의 나머지 쓰기 메서드(save/delete 등)
}
```

DI는 Spring이 주입 지점의 선언 타입으로 알아서 구분한다 — `GetAccountService`가 `AccountQueryRepository` 타입으로 주입받으면, 이 인터페이스를 구현하는 유일한 빈(`AccountRepositoryImpl`)이 연결되지만 `save`/`delete` 같은 쓰기 메서드는 애초에 `AccountQueryRepository` 타입에 노출되지 않으므로 `GetAccountService`는 컴파일 타임에 그 메서드들을 호출할 수 없다.

**이렇게 분리하는 이유:**
- `AccountRepository`(쓰기)에 변경이 생겨도(예: 저장 방식 변경) `AccountQueryRepository`의 계약 자체는 영향받지 않는다 — 결합도 감소.
- Query Service를 mock 테스트할 때 쓰기 메서드(`save`/`delete`)가 없는 좁은 인터페이스만 mock하면 되어 [testing.md](testing.md)의 Application 단위 테스트가 더 명확해진다.
- `harness.sh`의 `package-structure` 검사는 `application/query` 디렉토리의 존재 여부만 확인하므로 이 종류의 위반은 harness가 스스로 잡아내지 못한다(`docs/implementations/java-springboot.md` 참고) — 코드 리뷰로 보완해야 한다.

### 남은 gap — `GetTransactionsService`는 아직 쓰기용 `AccountRepository`를 사용

`GetTransactionsService`는 `findByAccountIdAndOwnerId`(소유권 확인) 외에도 `findTransactions`/`countTransactions`가 필요한데, 이 두 메서드는 아직 `AccountQueryRepository`에 추가되지 않았다 — 이 서비스는 여전히 `AccountRepository`(쓰기 인터페이스)를 그대로 주입받는다. `AccountQueryRepository`에 이 두 메서드를 확장하고 `GetTransactionsService`를 전환하는 것이 다음 단계다. 성능이 더 중요해지면(Aggregate 복원 없는 projection 쿼리 등) 아래 "확장 패턴"처럼 완전히 별도의 `AccountQueryImpl` 구현체로 분리할 수도 있다.

### 확장 패턴 — projection 전용 구현체가 필요해지면

읽기 경로에서 JPA 엔티티 전체(`AccountJpaEntity`, 연관된 `MoneyEmbeddable` 등)를 로딩하지 않고 응답 스키마에 맞는 컬럼만 골라 가져오고 싶다면, `AccountQueryRepository`를 `AccountRepositoryImpl`이 아닌 별도의 `AccountQueryImpl`(EntityManager로 projection JPQL 직접 작성)로 구현하는 선택지도 있다:

```java
// infrastructure/persistence/AccountQueryImpl.java — 별도 구현체를 둘 경우의 예시
@Repository
@RequiredArgsConstructor
public class AccountQueryImpl implements AccountQueryRepository {
    private final EntityManager em;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        // projection 쿼리로 필요한 컬럼만 조회 후 Account.reconstitute()로 조립하는 방식도 가능
    }
}
```

이 저장소는 현재 규모(Aggregate 필드 8개)에서는 `AccountRepositoryImpl`이 두 인터페이스를 함께 구현하는 것으로 충분하다고 보고 이 분리를 도입하지 않았다 — 여러 구현체가 필요해지면 Spring 빈 등록 시 `@Qualifier`로 명시한다.

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
| 읽기 진입점 | `XxxQueryService.method()` (`GetAccountService`는 `AccountQueryRepository` 사용) | `QueryHandler.execute()` |
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
