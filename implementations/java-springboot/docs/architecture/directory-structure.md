# 디렉토리 구조 (Spring Boot)

> 프레임워크 무관 원칙은 루트 [directory-structure.md](../../../../docs/architecture/directory-structure.md) 참고.

## 현재 실제 구조

`examples/src/main/java/com/example/accountservice/`의 실제 트리다 — `account`(1번째 도메인)와 `card`(2번째 도메인, Account BC와 Integration Event로 통신)를 함께 갖는다:

```
com.example.accountservice/
  AccountServiceApplication.java         # @SpringBootApplication 진입점

  account/
    domain/                              # 프레임워크 무의존 순수 도메인 (layer-architecture.md 참고)
      Account.java                       # Aggregate Root — 순수 객체, JPA 매핑은 AccountJpaEntity가 전담
      Transaction.java                   # 하위 Entity — 순수 객체, JPA 매핑은 TransactionJpaEntity가 전담
      Money.java                         # Value Object — 순수 record, JPA 매핑은 MoneyEmbeddable이 전담
      AccountStatus.java                 # 상태 enum
      TransactionType.java               # 거래 유형 enum
      AccountFindQuery.java              # 동적 조회 조건 record
      AccountException.java              # 도메인 예외 + ErrorCode enum
      AccountRepository.java             # Repository 인터페이스
      AccountsWithCount.java             # 목록+개수 병합 반환용 record
      TransactionsWithCount.java
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
        DeleteAccountService.java        # soft delete — CLOSED 상태 계좌만 삭제
        CreateAccountCommand.java        # record
        DepositCommand.java
        WithdrawCommand.java
        SuspendAccountCommand.java
        ReactivateAccountCommand.java
        CloseAccountCommand.java
        DeleteAccountCommand.java
        CreateAccountResult.java
        TransactionResult.java
      query/                             # 읽기 유스케이스
        AccountQuery.java                # Query 인터페이스 — 쓰기용 AccountRepository와 별개 (cqrs-pattern.md 참고)
        GetAccountService.java           # @Service @Transactional(readOnly = true) — AccountQuery 사용
        GetTransactionsService.java      # AccountQuery 사용 (findTransactions — 목록+개수 병합 반환)
        GetAccountResult.java
        GetTransactionsResult.java
      event/                             # Outbox가 드레인한 이벤트를 처리하는 Domain Event Handler
        AccountCreatedEventHandler.java  # @Component — outbox.OutboxEventHandler 구현
        MoneyDepositedEventHandler.java
        MoneyWithdrawnEventHandler.java
        AccountSuspendedEventHandler.java   # 알림 발송 + account.suspended.v1 Integration Event 적재
        AccountReactivatedEventHandler.java
        AccountClosedEventHandler.java      # 알림 발송 + account.closed.v1 Integration Event 적재
      integrationevent/                  # Card BC로 발행하는 공개 계약 (cross-domain-communication.md 참고)
        AccountSuspendedIntegrationEventV1.java
        AccountClosedIntegrationEventV1.java
      service/                           # 도메인 스코프 Technical Service — notification (아래 참고)
        NotificationService.java         # 인터페이스 — Technical Service

    infrastructure/
      persistence/
        AccountJpaEntity.java            # domain.Account의 JPA 매핑 전용 대응물 — @Entity
        TransactionJpaEntity.java        # domain.Transaction의 JPA 매핑 전용 대응물 — @Entity
        MoneyEmbeddable.java             # domain.Money의 JPA 매핑 전용 대응물 — @Embeddable
        AccountMapper.java               # Account ↔ AccountJpaEntity 변환 전담 (package-private)
        TransactionMapper.java           # Transaction ↔ TransactionJpaEntity 변환 전담 (package-private)
        AccountJpaRepository.java        # JpaRepository<AccountJpaEntity, Long> 확장
        TransactionJpaRepository.java    # JpaRepository<TransactionJpaEntity, Long> 확장
        AccountRepositoryImpl.java       # @Repository @Transactional — AccountRepository + AccountQuery 구현체 (Outbox 저장 포함)
      notification/                      # 도메인 스코프 Technical Service 구현 — account만 사용 (아래 참고)
        NotificationServiceImpl.java     # @Component — SES 구현체
        SesConfig.java                   # @Configuration — SesClient 빈
        persistence/
          SentEmail.java                 # @Entity — 발송 이력
          SentEmailRepository.java       # JpaRepository 직접 확장 (아래 참고)

    interfaces/
      rest/
        AccountController.java           # @RestController
        CreateAccountRequest.java        # record — Interface DTO
        DepositRequest.java
        WithdrawRequest.java
        ErrorResponse.java                # record — statusCode/code/message/error 4필드 (error-handling.md 참고)

  card/                                  # 2번째 도메인 — Account BC와 Integration Event로 통신
    domain/
      Card.java                          # Aggregate Root
      CardStatus.java
      CardException.java
      CardRepository.java
    application/
      command/
        IssueCardService.java
        CancelCardsByAccountService.java    # Account 종료 Integration Event 수신 시 카드 일괄 취소
        SuspendCardsByAccountService.java   # Account 정지 Integration Event 수신 시 카드 일괄 정지
        IssueCardCommand.java
        CancelCardsByAccountCommand.java
        SuspendCardsByAccountCommand.java
        IssueCardResult.java
      query/
        CardQuery.java
        GetCardService.java
        GetCardResult.java
      event/                             # 수신한 Account Integration Event를 처리하는 Handler
        AccountSuspendedIntegrationEventHandler.java
        AccountClosedIntegrationEventHandler.java
        AccountIntegrationEventPayload.java
      adapter/                           # Account BC 조회용 ACL 인터페이스 (cross-domain.md 참고)
        AccountAdapter.java
    infrastructure/
      AccountAdapterImpl.java            # AccountAdapter 구현 — Account BC를 동기 조회
      persistence/
        CardJpaEntity.java
        CardMapper.java
        CardJpaRepository.java
        CardRepositoryImpl.java
    interfaces/
      rest/
        CardController.java
        IssueCardRequest.java

  auth/                                  # 인증/가입 — Account 도메인과 분리된 별도 패키지
    domain/
      Credential.java                    # Aggregate Root — userId + bcrypt 해시
      CredentialsWithCount.java
      CredentialFindQuery.java
      CredentialRepository.java
      AuthException.java
    application/
      command/
        SignInService.java                # 저장된 해시와 비교 후 토큰 발급
        SignUpService.java                # 중복 확인 → 해싱 → 저장
        SignInCommand.java
        SignUpCommand.java
        SignInResult.java
      query/
        CredentialQuery.java
      service/
        PasswordHasher.java               # 인터페이스 — Technical Service
    infrastructure/
      BCryptPasswordHasher.java           # PasswordHasher 구현체
      persistence/
        CredentialJpaEntity.java
        CredentialMapper.java
        CredentialJpaRepository.java
        CredentialRepositoryImpl.java
    interfaces/
      rest/
        AuthController.java
        SignInRequest.java
        SignUpRequest.java

  common/                                # 도메인 무관 공유 인프라 (shared-modules.md 참고)
    IdGenerator.java                     # 32자리 hex ID 생성 유틸 (aggregate-id.md 참고)
    web/
      CorrelationIdFilter.java           # Correlation ID — MDC 전파
      RequestLoggingInterceptor.java
      RateLimitFilter.java
      GlobalExceptionHandler.java        # @RestControllerAdvice — 전역 예외 처리
    service/
      SecretService.java                 # 인터페이스 — Secrets Manager 접근
    infrastructure/
      SecretServiceImpl.java             # TTL 캐시 포함 구현체
    config/
      SecretsEnvironmentPostProcessor.java   # prod 프로필에서 jwt.secret을 기동 초기에 주입

  config/                                # 관심사별 @ConfigurationProperties (config.md 참고)
    AwsProperties.java
    SesProperties.java
    JwtProperties.java
    SecurityConfig.java                  # JWT SecurityFilterChain
    WebConfig.java                       # Filter/Interceptor 등록

  outbox/                                # 도메인 무관 공유 인프라 (shared-modules.md 참고) — Account 전용 아님
    OutboxEvent.java                     # @Entity — Outbox 테이블
    OutboxEventJpaRepository.java
    OutboxEventHandler.java              # 이벤트 타입별 Handler가 구현하는 인터페이스
    OutboxWriter.java                    # Repository.save() 트랜잭션 안에서 이벤트를 Outbox 행으로 적재
    OutboxPoller.java                    # @Scheduled(fixedDelay=1000) — Outbox 테이블을 폴링해 SQS로 발행 (domain-events.md 참고)
    OutboxConsumer.java                  # SmartLifecycle — SQS 수신 후 OutboxEventHandler로 라우팅 (domain-events.md 참고)
```

테스트는 `src/test/java/com/example/accountservice/`에 동일한 패키지를 미러링한다 (Gradle 표준 소스셋 레이아웃).

---

## `interfaces`(복수형) — Java 예약어 회피

Java는 `interface`가 언어 키워드이므로 패키지명으로 쓸 수 없다. 루트 문서의 `interface/`(단수)를 이 저장소는 `interfaces/`(복수형)로 표기한다 — go/kotlin-springboot도 각자의 언어 제약에 맞춰 조정하는 지점과 동일한 종류의 언어별 타협이다.

---

## `notification` — `account` 도메인 내부 Technical Service

알림(이메일) 발송은 `account/`와 형제인 최상위 패키지가 아니라 `account/application/service/`, `account/infrastructure/notification/`으로 **`account` 도메인 내부**에 배치되어 있다 — 실제로 `account`만 이 기능을 사용하기 때문이다. [domain-service.md](../../../../docs/architecture/domain-service.md)의 Technical Service 패턴 예시를 그대로 보여준다:

1. **Technical Service 인터페이스/구현 분리** — `account/application/service/NotificationService`(인터페이스)와 `account/infrastructure/notification/NotificationServiceImpl`(구현체) 분리.
2. **알림을 위한 자기 완결적 저장소** — `SentEmail`/`SentEmailRepository`(`account/infrastructure/notification/persistence/`)가 발송 이력을 독자적으로 관리하며, `Account`/`Transaction`의 Repository와 공유하지 않는다.

`account/infrastructure/notification/`에는 `domain/` 패키지가 없다 — Aggregate가 아니라 순수 기술 서비스(이메일 발송)이기 때문이다. `SentEmail`은 이력 기록용 Entity일 뿐 비즈니스 불변식을 캡슐화하는 Aggregate Root가 아니므로 `infrastructure/notification/persistence/`에 바로 둔다.

`SentEmailRepository`는 `AccountRepository`처럼 별도 인터페이스+구현체로 분리하지 않고 Spring Data `JpaRepository`를 직접 확장한 인터페이스 하나로 끝난다 — 발송 이력은 비즈니스 규칙이 없는 단순 기록이라 Repository 패턴의 인터페이스/구현 분리가 주는 이점(교체 가능성, 도메인 순수성 보호)이 필요 없다는 실용적 판단이다. [repository-pattern.md](repository-pattern.md)의 "Aggregate Root에만 전용 Repository 인터페이스"를 참고.

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
| Domain Event Handler | `application/event/` | `<DomainEvent>Handler`, `outbox.OutboxEventHandler` 구현 | `AccountCreatedEventHandler` |
| Technical Service 인터페이스 | `application/service/` | `<Concern>Service` | `NotificationService` |
| Technical Service 구현체 | `infrastructure/<concern>/` | `<Concern>ServiceImpl` | `NotificationServiceImpl`(`account/infrastructure/notification/`) |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller` | `AccountController` |
| Interface DTO | `interfaces/rest/` | `<Verb><Noun>Request`, `record` | `DepositRequest` |

---

## 공용 인프라 배치 기준

루트가 요구하는 `common/`(ID 생성 유틸 등)과 `config/`(관심사별 설정)가 모두 최상위 패키지로 존재한다. 배치 기준:

| 디렉토리 | 포함 내용 | 관련 문서 |
|---------|----------|----------|
| `common/` | `IdGenerator`, `CorrelationIdFilter`/`RequestLoggingInterceptor`/`RateLimitFilter`/`GlobalExceptionHandler`(`web/`), `SecretService`(`service/`+`infrastructure/`) | [aggregate-id.md](aggregate-id.md), [cross-cutting-concerns.md](cross-cutting-concerns.md), [secret-manager.md](secret-manager.md) |
| `config/` | `AwsProperties`, `SesProperties`, `JwtProperties` 등 `@ConfigurationProperties`, `SecurityConfig`, `WebConfig` | [config.md](config.md) |
| `outbox/` | `OutboxEvent`, `OutboxWriter`, `OutboxPoller`, `OutboxConsumer`, `OutboxEventHandler` | [domain-events.md](domain-events.md) |

이들은 특정 도메인(`account`/`card`/`auth`)에 속하지 않는 프로젝트 공용 코드이므로 도메인 패키지 밖, `com.example.accountservice` 바로 아래 최상위 패키지로 둔다. 반대로 `notification`처럼 실제로는 한 도메인만 사용하는 Technical Service는 공용 인프라가 아니라 그 도메인 내부(`account/infrastructure/notification/`)에 둔다 — 여러 도메인이 실제로 공유하게 되기 전까지는 최상위로 끌어올리지 않는다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어별 책임과 의존 방향
- [repository-pattern.md](repository-pattern.md) — Repository 인터페이스/구현 분리
- [tactical-ddd.md](tactical-ddd.md) — Aggregate/Entity/Value Object 배치
