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

**왜 `REQUIRES_NEW`인가**: 이 메서드는 `AccountCreatedEventHandler` 같은 `outbox/OutboxEventHandler` 구현체를 거쳐, `OutboxRelay.processPending()`(그 자체가 `@Transactional`)의 트랜잭션 안에서 호출된다 — 계좌 저장 트랜잭션은 그 시점에 이미 커밋되어 끝난 뒤다([domain-events.md](domain-events.md) 참고). `sendEmail()`이 `processPending()`의 트랜잭션을 그대로 이어받았다면(`REQUIRED`, 기본값), SES 호출 실패 시 발생한 예외가 그 트랜잭션을 **rollback-only**로 표시해버린다 — `OutboxRelay`가 개별 이벤트를 try-catch로 감싸 다음 이벤트 처리를 계속하더라도, Spring은 예외가 프록시 경계를 넘는 순간 트랜잭션을 rollback-only로 표시하므로 이번 배치에서 이미 성공적으로 처리한 다른 이벤트들의 `processed=true` 커밋까지 함께 실패한다. `REQUIRES_NEW`는 별도의 물리 트랜잭션을 열어 이 전이를 차단해, 한 이벤트의 발송 실패가 같은 배치의 다른 이벤트 처리 결과를 오염시키지 않게 한다.

| 전파 속성 | 동작 | 이 저장소에서 사용 위치 |
|---|---|---|
| `REQUIRED`(기본값) | 기존 트랜잭션이 있으면 참여, 없으면 새로 시작 | `CreateAccountService`, `DepositService` 등 모든 Command Service, `OutboxRelay.processPending()` |
| `REQUIRES_NEW` | 항상 새 물리 트랜잭션 시작, 기존 트랜잭션은 일시 중단 | `NotificationServiceImpl.sendEmail()` |
| `readOnly = true` | dirty checking/flush 생략 (성능 최적화, 전파 속성은 아님) | 모든 Query Service |

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

## 마이그레이션 — Flyway로 관리 (더 이상 gap 아님)

루트 원칙: 스키마 변경은 마이그레이션 파일로 관리한다. `ddl-auto: update`/`synchronize` 같은 자동 스키마 동기화는 **개발 환경 전용**이다. 이 예제는 Flyway를 도입해 이 원칙을 따른다.

```groovy
// build.gradle
implementation 'org.flywaydb:flyway-core'
implementation 'org.flywaydb:flyway-database-postgresql'
```

```yaml
# application.yml
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
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    amount BIGINT,
    currency VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deleted_at TIMESTAMP(6),
    CONSTRAINT uk_accounts_account_id UNIQUE (account_id)
);

-- V2__create_transactions.sql, V3__create_outbox.sql, V4__create_sent_email.sql — 나머지 3개 테이블도 동일한 방식
```

`amount`/`currency` 컬럼명에 `balance_`/`transaction amount` 같은 접두어가 없는 이유: `Account.balance`/`Transaction.amount` 모두 `@Embedded Money`이고 `@AttributeOverride`를 쓰지 않아 Hibernate가 `Money`의 필드명(`amount`, `currency`)을 그대로 컬럼명으로 쓴다. 두 테이블에 각각 있으므로 이름이 겹쳐도 문제는 없지만, 이 마이그레이션은 (아직 고치지 않은) 기존 스키마를 있는 그대로 옮겨 담은 것이다.

`ddl-auto: validate`는 Hibernate가 스키마를 변경하지 않고 엔티티 매핑과 실제 스키마가 일치하는지만 검사한다 — 불일치 시 기동이 실패하므로(fail-fast), 마이그레이션 누락을 배포 전에 잡아낸다. 실제로 빈 DB에 앱을 기동해 Flyway가 4개 마이그레이션을 자동 적용하고 `ddl-auto: validate`가 통과하는 것까지 확인함.

**테스트 환경은 예외**: `AccountControllerE2ETest`/`NotificationE2ETest`의 `@DynamicPropertySource`가 `ddl-auto: create-drop` + `spring.flyway.enabled: false`를 쓰는 것은 root가 명시한 대로 "개발/테스트 전용" 허용 범위 안에 있다 — Testcontainers가 매 테스트 실행마다 새 컨테이너를 띄우므로 자동 스키마 생성이 오히려 적절하고, Flyway와 create-drop을 동시에 켜면 스키마가 중복 생성되므로 테스트에서는 Flyway를 꺼둔다.

---

## 원칙 요약

| 원칙 | 이 저장소 상태 |
|---|---|
| 트랜잭션은 컨텍스트-로컬로 암묵 전파 | `@Transactional` AOP 프록시로 대체 — 준수 |
| `REQUIRES_NEW`로 부수 효과 격리 | `NotificationServiceImpl.sendEmail()` — 준수 |
| 모든 테이블에 `createdAt`/`updatedAt`/`deletedAt` | `Account`는 3컬럼 모두 보유 — 준수 |
| 삭제는 기본적으로 soft delete | `deletedAt` 컬럼은 있으나 실제로 설정하는 코드 없음 — **위반 (알려진 gap)** |
| 스키마 변경은 마이그레이션으로 관리 | Flyway(`db/migration/`) + `ddl-auto: validate` — 준수 |

### 관련 문서

- [repository-pattern.md](repository-pattern.md) — Repository의 `delete<Noun>` 메서드 부재
- [domain-events.md](domain-events.md) — Outbox 저장도 같은 트랜잭션에서 처리
- [config.md](config.md) — 환경별 `ddl-auto` 분기
