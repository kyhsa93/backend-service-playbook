# Repository 패턴 (Spring Boot / Spring Data JPA)

> 프레임워크 무관 원칙은 루트 [repository-pattern.md](../../../../docs/architecture/repository-pattern.md) 참고.

## 인터페이스(domain) + 구현체(infrastructure) 분리

TypeScript의 `abstract class` 대신 Java `interface`를 DI 토큰으로 사용한다 — Java/Spring은 인터페이스 자체가 런타임에 유효한 DI 타입이므로 별도의 우회가 필요 없다.

```java
// account/domain/AccountRepository.java — 실제 코드, Spring 완전 무의존
package com.example.accountservice.account.domain;

public interface AccountRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);
    List<Account> findAll(AccountFindQuery query);
    long countAll(AccountFindQuery query);
    void save(Account account);
    List<Transaction> findTransactions(String accountId, int page, int take);
    long countTransactions(String accountId);
}
```

```java
// account/infrastructure/persistence/AccountRepositoryImpl.java — 실제 코드 (일부)
@Repository
@RequiredArgsConstructor
public class AccountRepositoryImpl implements AccountRepository {
    private final AccountJpaRepository jpaRepository;
    private final TransactionJpaRepository transactionJpaRepository;
    private final EntityManager em;

    @Override
    public Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId) {
        return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId);
    }
}
```

Application Service는 `AccountRepository` 인터페이스 타입으로 주입받고, Spring이 클래스패스의 유일한 구현체(`AccountRepositoryImpl`, `@Repository`)를 자동 바인딩한다. 구현체가 여러 개 필요해지면 `@Qualifier`로 명시한다.

---

## 2단 Repository — 도메인 Repository와 Spring Data JPA Repository

이 저장소는 Repository를 2개 층으로 나눈다:

```java
// infrastructure/persistence/AccountJpaRepository.java — Spring Data가 구현을 자동 생성
public interface AccountJpaRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountIdAndOwnerIdAndDeletedAtIsNull(String accountId, String ownerId);
}
```

| | `AccountRepository` (domain) | `AccountJpaRepository` (infrastructure) |
|---|---|---|
| 역할 | 도메인이 필요로 하는 조회/저장 계약 | Spring Data JPA의 CRUD + 파생 쿼리 |
| 구현 방식 | `AccountRepositoryImpl`이 수동 구현 | Spring Data가 메서드 이름을 파싱해 자동 구현 |
| 사용처 | Application Service가 주입받음 | `AccountRepositoryImpl` 내부에서만 사용 |

**`AccountRepositoryImpl`이 이 둘을 조합하는 이유**: `findAll(AccountFindQuery)`처럼 필터 조건이 동적으로 조합되는 조회는 Spring Data의 파생 쿼리 메서드 이름만으로 표현하기 어렵다 — `EntityManager`로 JPQL을 직접 조립한다(아래 "동적 필터" 참고). 반면 `findByAccountIdAndOwnerIdAndDeletedAtIsNull`처럼 고정된 조건은 Spring Data 파생 쿼리로 충분하므로 `AccountJpaRepository`에 위임한다. 두 접근을 같은 클래스(`AccountRepositoryImpl`) 안에서 상황에 맞게 섞어 쓴다.

---

## Repository 메서드 네이밍 — 알려진 gap

루트 규칙: 조회는 항상 `find<Noun>s`(복수형) 하나로 통일하고, 단건 조회는 `take: 1` + `.pop()` 패턴을 사용한다. 저장은 `save<Noun>`, 삭제는 `delete<Noun>`.

현재 `AccountRepository`는 이 규칙에서 두 가지가 벗어나 있다:

```java
public interface AccountRepository {
    Optional<Account> findByAccountIdAndOwnerId(String accountId, String ownerId);  // ← 단건 전용 별도 메서드
    List<Account> findAll(AccountFindQuery query);
    long countAll(AccountFindQuery query);
    void save(Account account);
    // delete 메서드 자체가 없음 — 아래 참고
}
```

**1) 단건 조회가 별도 메서드다**: `findByAccountIdAndOwnerId`는 Spring Data 파생 쿼리 관례를 그대로 따른 것으로, Java/Spring Data 생태계에서는 자연스럽고 타입 안전하다. 다만 root의 "조회 경로를 하나로 통일" 취지를 살리려면 `findAll(query)`에 `accountId`+`ownerId`+`take:1` 필터를 태워 통일할 수 있다(대안은 [api-response.md](api-response.md) 참고). 이 저장소는 실용적 트레이드오프로 현재 방식을 유지하고 있다.

**2) `delete<Noun>` 메서드가 없다**: `AccountRepository`에 삭제 메서드 자체가 정의되어 있지 않다 — 계좌 "종료"는 `Account.close()`로 `status = CLOSED`를 설정할 뿐 soft delete(`deletedAt` 설정)를 거치지 않는다. 올바르게 추가한다면:

```java
public interface AccountRepository {
    // ... 기존 메서드
    void delete(String accountId);   // 신규 — soft delete
}
```

```java
// AccountRepositoryImpl — soft delete 구현
@Override
public void delete(String accountId) {
    jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId)
            .ifPresent(account -> {
                account.markDeleted();          // Account에 deletedAt 설정 메서드 추가 필요
                jpaRepository.save(account);
            });
}
```

soft delete 자체의 상세(계좌 종료 `close()`와 삭제 `delete()`의 의미 차이, `deletedAt` 실제 배선)는 [persistence.md](persistence.md)에서 다룬다.

---

## 공통 컬럼 — `createdAt`/`updatedAt`/`deletedAt`

`Account`는 이 세 컬럼을 모두 갖지만 공통 베이스 클래스로 추출되어 있지 않다 — `Account`와 `Transaction`이 각자 필드를 반복 선언한다. JPA는 `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener.class)`로 이를 추출할 수 있다:

```java
// infrastructure/persistence/BaseAuditable.java — 제안, 도입 시 참고
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditable {
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime deletedAt;
}
```

`@EnableJpaAuditing`을 `@SpringBootApplication` 클래스에 추가하면 `createdAt`/`updatedAt`이 자동 설정된다. 이 저장소는 현재 `Account.create()`/도메인 메서드 내부에서 `LocalDateTime.now()`를 수동으로 설정하는 방식을 쓴다 — Auditing으로 전환하면 도메인 메서드가 타임스탬프 관리에서 자유로워지지만, `@Entity`가 Domain 레이어에 있는 현재 구조상 `@MappedSuperclass`를 domain에 두는 것 자체가 [layer-architecture.md](layer-architecture.md)의 gap을 더 키우는 선택이라는 점을 함께 고려해야 한다.

---

## 동적 필터 패턴 — `EntityManager` + JPQL 조립

```java
// AccountRepositoryImpl.buildJpql() — 실제 코드
private String buildJpql(AccountFindQuery query, boolean count) {
    StringBuilder sb = new StringBuilder(count
            ? "SELECT COUNT(a) FROM Account a WHERE a.deletedAt IS NULL"
            : "SELECT a FROM Account a WHERE a.deletedAt IS NULL");
    if (query.accountId() != null && !query.accountId().isBlank()) sb.append(" AND a.accountId = :accountId");
    if (query.ownerId() != null && !query.ownerId().isBlank()) sb.append(" AND a.ownerId = :ownerId");
    if (query.status() != null && !query.status().isEmpty()) sb.append(" AND a.status IN :status");
    if (!count) sb.append(" ORDER BY a.accountId DESC");
    return sb.toString();
}
```

값이 있을 때만 조건을 추가하는 방식으로 root의 "동적 필터" 원칙을 구현한다. 입력은 `account/domain/AccountFindQuery.java`(record)로 타입화되어 있다:

```java
public record AccountFindQuery(int page, int take, String accountId, String ownerId, List<String> status) {}
```

**대안 — Spring Data JPA Specification**: 조건이 더 늘어나면 문자열 JPQL 조립 대신 `Specification<Account>`(JPA Criteria API 래퍼)를 쓰는 것이 타입 안전성과 가독성 면에서 낫다:

```java
public interface AccountJpaRepository extends JpaRepository<Account, Long>, JpaSpecificationExecutor<Account> {}

public class AccountSpecifications {
    public static Specification<Account> hasOwnerId(String ownerId) {
        return (root, query, cb) -> ownerId == null ? null : cb.equal(root.get("ownerId"), ownerId);
    }
}
// 호출: jpaRepository.findAll(where(hasOwnerId(id)).and(hasStatus(status)), pageable)
```

현재 규모(필터 3개)에서는 문자열 조립도 충분히 관리 가능한 수준이라 이 저장소는 `EntityManager` 방식을 유지하고 있다.

---

## Repository의 Cascade 저장 — `save()`가 하위 Entity까지 함께 처리

```java
// AccountRepositoryImpl.save() — 실제 코드
@Override
public void save(Account account) {
    jpaRepository.save(account);
    List<Transaction> pending = account.pullPendingTransactions();   // Aggregate가 보류 중인 하위 Entity 반환
    if (!pending.isEmpty()) {
        transactionJpaRepository.saveAll(pending);
    }
}
```

`Account.deposit()`/`withdraw()`가 생성한 `Transaction`은 `pendingTransactions`(`@Transient`)에 임시로 쌓이고, `save()` 호출 한 번에 `Account`와 `Transaction` 모두가 저장된다 — Application Service는 `Transaction`을 별도로 저장하지 않는다. Aggregate 단위로 저장 책임이 캡슐화된 root 원칙의 실현이다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — Repository 인터페이스 배치, 레이어 의존 방향
- [persistence.md](persistence.md) — 트랜잭션 전파, soft delete 배선, 마이그레이션
- [cqrs-pattern.md](cqrs-pattern.md) — Query Service가 이 Repository를 직접 쓰는 gap
- [domain-events.md](domain-events.md) — Repository에서 Outbox를 함께 저장하는 올바른 패턴
