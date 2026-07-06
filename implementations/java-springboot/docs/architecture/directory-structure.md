# 디렉토리 구조 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [directory-structure.md](../../../../docs/architecture/directory-structure.md) 참고.

## 현재 실제 구조

`examples/src/main/java/com/example/accountservice/`의 실제 트리다 (guide.md는 이를 `com.example.orderservice`/`order` 도메인으로 서술했지만, 실제 코드는 이미 `accountservice`/`account`로 전환되어 있다):

```
com.example.accountservice/
  AccountServiceApplication.java         # @SpringBootApplication 진입점

  account/
    domain/                              # 프레임워크 무의존이어야 하는 레이어 (알려진 gap, layer-architecture.md 참고)
      Account.java                       # Aggregate Root — 현재 @Entity 겸용
      Transaction.java                   # 하위 Entity — 현재 @Entity 겸용
      Money.java                         # Value Object — 현재 @Embeddable record
      AccountStatus.java                 # 상태 enum
      TransactionType.java               # 거래 유형 enum
      AccountFindQuery.java              # 동적 조회 조건 record
      AccountException.java              # 도메인 예외 + ErrorCode enum
      AccountRepository.java             # Repository 인터페이스
      AccountCreatedEvent.java           # Domain Event (record)
      MoneyDepositedEvent.java
      MoneyWithdrawnEvent.java
      AccountSuspendedEvent.java
      AccountReactivatedEvent.java
      AccountClosedEvent.java

    application/
      command/                           # 쓰기 유스케이스
        CreateAccountService.java        # @Service @Transactional
        DepositService.java
        WithdrawService.java
        SuspendAccountService.java
        ReactivateAccountService.java
        CloseAccountService.java
        CreateAccountCommand.java        # record
        DepositCommand.java
        WithdrawCommand.java
        SuspendAccountCommand.java
        ReactivateAccountCommand.java
        CloseAccountCommand.java
        CreateAccountResult.java
        TransactionResult.java
      query/                             # 읽기 유스케이스
        GetAccountService.java           # @Service @Transactional(readOnly = true)
        GetTransactionsService.java
        GetAccountResult.java
        GetTransactionsResult.java
      event/                             # Domain Event 구독
        AccountNotificationListener.java # @Component + @EventListener (현재는 in-process, domain-events.md 참고)

    infrastructure/
      persistence/
        AccountJpaRepository.java        # JpaRepository<Account, Long> 확장
        TransactionJpaRepository.java
        AccountRepositoryImpl.java       # @Repository — AccountRepository 구현체

    interfaces/
      rest/
        AccountController.java           # @RestController
        CreateAccountRequest.java        # record — Interface DTO
        DepositRequest.java
        WithdrawRequest.java
        ErrorResponse.java                # record — 현재 2필드 (알려진 gap, error-handling.md 참고)

  notification/                          # 두 번째 Bounded Context 예시 — Technical Service 패턴
    application/
      service/
        NotificationService.java         # 인터페이스 — Technical Service
    infrastructure/
      NotificationServiceImpl.java       # @Component — SES 구현체
      SesConfig.java                     # @Configuration — SesClient 빈
      persistence/
        SentEmail.java                   # @Entity — 발송 이력
        SentEmailRepository.java         # JpaRepository 직접 확장 (아래 참고)
```

테스트는 `src/test/java/com/example/accountservice/`에 동일한 패키지를 미러링한다 (Gradle 표준 소스셋 레이아웃).

---

## `interfaces`(복수형) — Java 예약어 회피

Java는 `interface`가 언어 키워드이므로 패키지명으로 쓸 수 없다. 루트 문서의 `interface/`(단수)를 이 저장소는 `interfaces/`(복수형)로 표기한다 — go/kotlin-springboot도 각자의 언어 제약에 맞춰 조정하는 지점과 동일한 종류의 언어별 타협이다.

---

## `notification` 모듈 — Technical Service 겸 두 번째 BC 예시

`notification/`은 `account/`와 별개의 최상위 패키지다. 이 저장소에서 두 가지 역할을 겸한다:

1. **Technical Service 패턴 예시** — `application/service/NotificationService`(인터페이스)와 `infrastructure/NotificationServiceImpl`(구현체) 분리가 [domain-service.md](../../../../docs/architecture/domain-service.md)의 Technical Service 패턴을 그대로 보여준다.
2. **알림을 위한 자기 완결적 저장소** — `SentEmail`/`SentEmailRepository`가 발송 이력을 독자적으로 관리하며, `account` 도메인의 Repository와 공유하지 않는다.

`notification`에는 `domain/` 패키지가 없다 — Aggregate가 아니라 순수 기술 서비스(이메일 발송)이기 때문이다. `SentEmail`은 이력 기록용 Entity일 뿐 비즈니스 불변식을 캡슐화하는 Aggregate Root가 아니므로 `infrastructure/persistence/`에 바로 둔다.

`SentEmailRepository`(`notification/infrastructure/persistence/`)는 `AccountRepository`처럼 별도 인터페이스+구현체로 분리하지 않고 Spring Data `JpaRepository`를 직접 확장한 인터페이스 하나로 끝난다 — 발송 이력은 비즈니스 규칙이 없는 단순 기록이라 Repository 패턴의 인터페이스/구현 분리가 주는 이점(교체 가능성, 도메인 순수성 보호)이 필요 없다는 실용적 판단이다. [repository-pattern.md](repository-pattern.md)의 "Aggregate Root에만 전용 Repository 인터페이스"를 참고.

---

## 파일·클래스 네이밍 규칙

| 대상 | 규칙 | 예시 |
|------|------|------|
| 파일명·클래스명 | `PascalCase`, 파일명 = public 클래스명 | `AccountRepository.java` |
| 패키지명 | 소문자, 하이픈 없음 | `com.example.accountservice.account.domain` |
| 상수 | `UPPER_SNAKE_CASE` | `MAX_TRANSACTIONS_PER_PAGE` |
| 메서드·필드 | `camelCase` | `getAccountId()`, `pullDomainEvents()` |
| Enum 상수 | `UPPER_SNAKE_CASE` | `AccountStatus.ACTIVE` |

| 종류 | 위치 | 클래스명 패턴 | 예시 |
|------|------|-------------|------|
| Aggregate Root | `domain/` | 도메인 명사 | `Account` |
| 하위 Entity | `domain/` | 도메인 명사 | `Transaction` |
| Value Object | `domain/` | 개념명, `record` | `Money` |
| Domain Event | `domain/` | 과거형, `record` | `MoneyDepositedEvent` |
| Repository 인터페이스 | `domain/` | `<Aggregate>Repository` | `AccountRepository` |
| Repository 구현체 | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl` | `AccountRepositoryImpl` |
| Spring Data JPA 인터페이스 | `infrastructure/persistence/` | `<Aggregate>JpaRepository` | `AccountJpaRepository` |
| Command Service | `application/command/` | `<Verb><Noun>Service` | `CreateAccountService` |
| Query Service | `application/query/` | `Get<Noun>Service` | `GetAccountService` |
| Command | `application/command/` | `<Verb><Noun>Command`, `record` | `DepositCommand` |
| Result | `application/{command,query}/` | `<Verb><Noun>Result`, `record` | `GetAccountResult` |
| Domain Event Handler | `application/event/` | `<Domain>NotificationListener` 등 | `AccountNotificationListener` |
| Technical Service 인터페이스 | `application/service/` | `<Concern>Service` | `NotificationService` |
| Technical Service 구현체 | `infrastructure/` | `<Concern>ServiceImpl` | `NotificationServiceImpl` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller` | `AccountController` |
| Interface DTO | `interfaces/rest/` | `<Verb><Noun>Request`, `record` | `DepositRequest` |

---

## 공용 인프라 배치 기준 — 아직 부재

루트가 요구하는 `common/`(ID 생성 유틸), `outbox/`(도메인 이벤트 릴레이), `config/`(관심사별 설정)는 이 저장소에 아직 없다. 새로 추가할 때의 배치 기준:

| 디렉토리 | 포함 내용 | 관련 문서 |
|---------|----------|----------|
| `common/` | `IdGenerator` 등 순수 유틸 — 프레임워크 무의존 | [aggregate-id.md](aggregate-id.md) |
| `outbox/` | `OutboxEvent`, `OutboxRelay`, `EventConsumer` | [domain-events.md](domain-events.md) |
| `config/` | `AwsProperties`, `SesProperties` 등 `@ConfigurationProperties` | [config.md](config.md) |

이들은 특정 도메인(`account`, `notification`)에 속하지 않는 프로젝트 공용 코드이므로 도메인 패키지 밖, `com.example.accountservice` 바로 아래 최상위 패키지로 둔다 (`account/`, `notification/`과 같은 레벨).

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어별 책임과 의존 방향
- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [tactical-ddd.md](tactical-ddd.md) — Aggregate/Entity/Value Object 배치
