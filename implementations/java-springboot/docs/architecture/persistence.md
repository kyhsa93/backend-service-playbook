# 영속성 패턴 (Spring Boot / Spring Data JPA)

> 프레임워크 무관 원칙은 루트 [persistence.md](../../../../docs/architecture/persistence.md) 참고.

## 트랜잭션 전파 — `@Transactional`이 Unit of Work를 대체

root는 `AsyncLocalStorage`/`ThreadLocal` 기반 수동 TransactionManager를 설명하지만, Spring은 이를 선언적 `@Transactional`(AOP 프록시 기반)로 완전히 대체한다. 별도의 TransactionManager 클래스를 직접 구현할 필요가 없다.

```java
// application/command/CreateAccountService.java — 실제 코드
@Service
@RequiredArgsConstructor
@Transactional
public class CreateAccountService {
    private final AccountRepository accountRepository;
    // 메서드 전체가 하나의 물리 트랜잭션으로 실행된다
}
```

`@Transactional`이 메서드에 진입하는 순간 Spring이 트랜잭션을 시작하고, 정상 반환 시 커밋, unchecked 예외(`RuntimeException` 하위) 발생 시 롤백한다. 트랜잭션 컨텍스트는 스레드 로컬로 전파되므로, 같은 호출 스택 안에서 여러 Repository를 호출해도 자동으로 같은 트랜잭션에 묶인다 — root의 `AsyncLocalStorage`가 하는 역할을 Spring 프록시가 대신한다.

---

## `REQUIRES_NEW` — 알림 발송 트랜잭션 격리 (이미 구현됨)

`notification/infrastructure/NotificationServiceImpl`이 이 저장소에서 유일하게 실제로 사용 중인 전파 속성 커스터마이징이다:

```java
// notification/infrastructure/NotificationServiceImpl.java — 실제 코드
@Component
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final SesClient sesClient;
    private final SentEmailRepository sentEmailRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(String accountId, String eventType, String recipient, String subject, String body) {
        SendEmailResponse response = sesClient.sendEmail(/* ... */);
        SentEmail sentEmail = SentEmail.create(accountId, eventType, recipient, subject, response.messageId());
        sentEmailRepository.save(sentEmail);
    }
}
```

**왜 `REQUIRES_NEW`인가**: `AccountNotificationListener`가 이 메서드를 호출하는 시점은 원본 계좌 커맨드(예: `CreateAccountService.create()`)의 트랜잭션 내부다(동기 `@EventListener`이므로). 만약 `sendEmail()`이 원본 트랜잭션을 그대로 이어받았다면(`REQUIRED`, 기본값), SES 호출 실패 시 발생한 예외가 원본 트랜잭션을 **rollback-only**로 표시해버려 계좌 생성 자체가 롤백된다 — 알림 실패가 핵심 비즈니스 트랜잭션을 오염시키는 것이다. `REQUIRES_NEW`는 별도의 물리 트랜잭션을 열어 이 전이를 차단한다.

| 전파 속성 | 동작 | 이 저장소에서 사용 위치 |
|---|---|---|
| `REQUIRED`(기본값) | 기존 트랜잭션이 있으면 참여, 없으면 새로 시작 | `CreateAccountService`, `DepositService` 등 모든 Command Service |
| `REQUIRES_NEW` | 항상 새 물리 트랜잭션 시작, 기존 트랜잭션은 일시 중단 | `NotificationServiceImpl.sendEmail()` |
| `readOnly = true` | dirty checking/flush 생략 (성능 최적화, 전파 속성은 아님) | 모든 Query Service |

**주의 — `REQUIRES_NEW`가 근본 해결책은 아니다**: 이는 "알림 실패가 계좌 생성 트랜잭션을 깨뜨리지 않게" 막을 뿐, 이벤트 발행 자체의 원자성 문제([domain-events.md](domain-events.md)의 Outbox gap)는 해결하지 못한다 — 앱이 이벤트 발행 직후 크래시하면 알림은 여전히 유실된다. `REQUIRES_NEW`와 Outbox는 서로 다른 문제를 다루는 별개의 패턴이다.

---

## Entity 공통 컬럼 — `createdAt`/`updatedAt`/`deletedAt`

`Account`는 세 컬럼을 모두 갖는다:

```java
@Column(nullable = false)
private LocalDateTime createdAt;

@Column(nullable = false)
private LocalDateTime updatedAt;

@Column
private LocalDateTime deletedAt;
```

`createdAt`/`updatedAt`은 도메인 메서드(`create()`, `deposit()` 등)가 `LocalDateTime.now()`로 수동 설정한다. `@CreatedDate`/`@LastModifiedDate`(Spring Data JPA Auditing) 자동화는 [repository-pattern.md](repository-pattern.md)의 공통 컬럼 절 참고.

---

## Soft Delete — 알려진 gap: `deletedAt`이 설정되지 않는다

루트 원칙: 삭제는 hard delete가 아니라 `deletedAt` 타임스탬프를 기록하는 soft delete를 기본으로 사용하며, 조회 시 `deletedAt IS NULL`이 기본 적용되어야 한다.

`Account`는 `deletedAt` 컬럼을 갖고 있고, 실제로 조회 쿼리들이 `deletedAt IS NULL` 조건을 이미 적용한다:

```java
// AccountRepositoryImpl — 실제 코드, 조회는 올바르게 필터링됨
Optional<Account> findByAccountIdAndOwnerId(...) {
    return jpaRepository.findByAccountIdAndOwnerIdAndDeletedAtIsNull(accountId, ownerId);
}
// buildJpql()도 항상 "WHERE a.deletedAt IS NULL"로 시작
```

**하지만 `deletedAt`을 실제로 설정하는 코드가 어디에도 없다.** `Account.close()`는 `status = CLOSED`로 상태만 바꿀 뿐 삭제가 아니다 — "계좌 종료"(비즈니스 상태 전이)와 "레코드 삭제"(관리적 행위)는 서로 다른 개념인데, 후자에 대응하는 도메인 메서드나 Repository 메서드가 존재하지 않는다. `AccountRepository`에 `delete<Noun>` 메서드가 없다는 것은 [repository-pattern.md](repository-pattern.md)에서도 지적한 지점이다.

### 올바른 배선 — Aggregate 메서드 + Repository 메서드 추가

```java
// domain/Account.java — 추가 필요
public void delete() {
    if (this.status != AccountStatus.CLOSED) {
        throw new AccountException(AccountException.ErrorCode.ACCOUNT_NOT_CLOSABLE_FOR_DELETE, "종료된 계좌만 삭제할 수 있습니다.");
    }
    this.deletedAt = LocalDateTime.now();
}
```

```java
// domain/AccountRepository.java — 추가 필요
void delete(String accountId);
```

```java
// AccountRepositoryImpl — 추가 필요
@Override
public void delete(String accountId) {
    jpaRepository.findByAccountIdAndDeletedAtIsNull(accountId).ifPresent(account -> {
        account.delete();          // 도메인 메서드로 불변식 검증 후 deletedAt 설정
        jpaRepository.save(account);
    });
}
```

**하위 Entity도 함께 soft delete**: `Transaction`(하위 Entity)에도 `deletedAt`을 전파해야 한다면, `Account.delete()` 내부에서 `Transaction`들에도 삭제를 반영하거나, Repository가 명시적으로 순서를 처리한다. 현재 `Transaction`은 `deletedAt` 컬럼 자체가 없다 — 계좌 삭제 시 거래 내역까지 숨길지는 감사(audit) 요구사항에 따라 결정한다.

---

## 마이그레이션 — 알려진 gap: `ddl-auto: update` + 마이그레이션 도구 부재

루트 원칙: 스키마 변경은 마이그레이션 파일로 관리한다. `ddl-auto: update`/`synchronize` 같은 자동 스키마 동기화는 **개발 환경 전용**이다.

`examples/src/main/resources/application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update    # ← 앱 설정, 테스트 설정(E2E의 DynamicPropertySource) 모두 이 값을 사용
```

`build.gradle`에도 Flyway/Liquibase 의존성이 없다. 즉 스키마는 Hibernate가 엔티티 애노테이션을 스캔해 매 기동 시 자동으로 맞추는 방식에 전적으로 의존한다 — 운영 환경에서 이 설정을 켜두면 배포 시 의도치 않은 컬럼 삭제/타입 변경이 발생할 수 있다(Hibernate의 `update` 모드는 컬럼 추가는 하지만 삭제는 하지 않는 등 동작이 예측하기 어렵다).

### 올바른 도입 — Flyway

```groovy
// build.gradle — 추가
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
```

```yaml
# application.yml — 수정
spring:
  jpa:
    hibernate:
      ddl-auto: validate     # 마이그레이션과 엔티티 매핑이 일치하는지만 검증. 자동 변경 없음
  flyway:
    enabled: true
    locations: classpath:db/migration
```

```sql
-- src/main/resources/db/migration/V1__create_accounts.sql
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(32) NOT NULL UNIQUE,
    owner_id VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    balance_amount BIGINT NOT NULL,
    balance_currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

-- V2__create_transactions.sql
CREATE TABLE transactions ( /* ... */ );
```

`ddl-auto: validate`로 바꾸면 Hibernate는 스키마를 변경하지 않고 엔티티 매핑과 실제 스키마가 일치하는지만 검사한다 — 불일치 시 기동이 실패하므로(fail-fast), 마이그레이션 누락을 배포 전에 잡아낸다.

**테스트 환경은 예외**: `AccountControllerE2ETest`/`NotificationE2ETest`의 `@DynamicPropertySource`가 `ddl-auto: create-drop`을 쓰는 것은 root가 명시한 대로 "개발/테스트 전용" 허용 범위 안에 있다 — Testcontainers가 매 테스트 실행마다 새 컨테이너를 띄우므로 자동 스키마 생성이 오히려 적절하다.

---

## 원칙 요약

| 원칙 | 이 저장소 상태 |
|---|---|
| 트랜잭션은 컨텍스트-로컬로 암묵 전파 | `@Transactional` AOP 프록시로 대체 — 준수 |
| `REQUIRES_NEW`로 부수 효과 격리 | `NotificationServiceImpl.sendEmail()` — 준수 |
| 모든 테이블에 `createdAt`/`updatedAt`/`deletedAt` | `Account`는 3컬럼 모두 보유 — 준수 |
| 삭제는 기본적으로 soft delete | `deletedAt` 컬럼은 있으나 실제로 설정하는 코드 없음 — **위반 (알려진 gap)** |
| 스키마 변경은 마이그레이션으로 관리 | `ddl-auto: update` + 마이그레이션 도구 없음 — **위반 (알려진 gap)** |

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository의 `delete<Noun>` 메서드 부재
- [domain-events.md](domain-events.md) — Outbox 저장도 같은 트랜잭션에서 처리
- [config.md](config.md) — 환경별 `ddl-auto` 분기
