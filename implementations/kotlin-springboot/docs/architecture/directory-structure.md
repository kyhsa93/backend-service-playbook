# 디렉토리 구조 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root directory-structure.md](../../../../docs/architecture/directory-structure.md) 참조.

## 실제 패키지 트리 — Account 도메인

`examples/src/main/kotlin/com/example/accountservice/`의 실제 구조다.

```
com.example.accountservice/
  AccountServiceApplication.kt       ← @SpringBootApplication 진입점

  common/                           ← 공용 인프라 (특정 BC 소속 아님)
    CorrelationIdFilter.kt            ← Filter, MDC에 Correlation ID 설정 (cross-cutting-concerns.md)
    RequestLoggingInterceptor.kt      ← HandlerInterceptor, 요청/응답 로깅 (cross-cutting-concerns.md)
    WebConfig.kt                      ← @Configuration, Interceptor 등록
    GenerateId.kt                     ← Aggregate ID 생성 유틸(top-level 함수) (aggregate-id.md)

  config/                           ← @ConfigurationProperties data class 모음 (config.md)
    AwsProperties.kt                  ← prefix="aws", @Validated
    SesProperties.kt                  ← prefix="ses", @Validated

  auth/                             ← 인증 공유 모듈 (authentication.md)
    application/
      AuthService.kt                   ← JWT 발급
    infrastructure/
      JwtAuthenticationFilter.kt       ← Bearer 토큰 검증 Filter
      SecurityConfig.kt                ← @Configuration, 화이트리스트 경로
    interfaces/
      rest/
        AuthController.kt              ← 로그인 엔드포인트

  secret/                           ← Secrets Manager 연동 (secret-manager.md)
    application/
      service/
        SecretService.kt                ← interface, Spring 무의존
    infrastructure/
      SecretServiceImpl.kt             ← @Component, TTL 캐시
      SecretManagerConfig.kt           ← @Configuration, SecretsManagerClient Bean
      SecretsEnvironmentPostProcessor.kt ← prod 프로파일에서 jwt.secret을 Environment에 주입, META-INF/spring.factories로 등록

  outbox/                           ← Domain Event Outbox (domain-events.md)
    OutboxEvent.kt                    ← @Entity
    OutboxEventJpaRepository.kt
    OutboxWriter.kt                   ← Repository.save() 트랜잭션 안에서 Outbox 행 적재
    OutboxPoller.kt                    ← @Scheduled 폴링으로 Outbox 테이블 → SQS 발행 (Command Service는 호출하지 않음)
    OutboxConsumer.kt                  ← SmartLifecycle, SQS long polling → EventHandlerRegistry로 라우팅
    EventHandlerRegistry.kt            ← eventType → 핸들러 매핑(Map 기반, when 분기 아님)

  account/
    domain/                          ← 프레임워크 무의존 (Spring/JPA import 없음, harness domain-purity 검사)
      Account.kt                     ← Aggregate Root — 순수 Kotlin, JPA 매핑은 AccountJpaEntity가 전담
      Transaction.kt                 ← 하위 Entity — 순수 Kotlin, JPA 매핑은 TransactionJpaEntity가 전담
      Money.kt                       ← Value Object — 순수 data class, JPA 매핑은 MoneyEmbeddable이 전담
      AccountStatus.kt               ← enum class
      TransactionType.kt             ← enum class
      AccountException.kt            ← sealed class 에러 계층
      AccountRepository.kt           ← Repository 인터페이스 (Spring 무의존 interface)
      AccountCreatedEvent.kt         ← Domain Event (data class)
      AccountSuspendedEvent.kt
      AccountReactivatedEvent.kt
      AccountClosedEvent.kt
      MoneyDepositedEvent.kt
      MoneyWithdrawnEvent.kt

    application/
      command/
        CreateAccountService.kt      ← @Service — 유스케이스 1개 = 클래스 1개
        CreateAccountCommand.kt      ← data class
        CreateAccountResult.kt       ← data class
        DepositService.kt / DepositCommand.kt
        WithdrawService.kt / WithdrawCommand.kt
        SuspendAccountService.kt / SuspendAccountCommand.kt
        ReactivateAccountService.kt / ReactivateAccountCommand.kt
        CloseAccountService.kt / CloseAccountCommand.kt
        TransactionResult.kt         ← data class (Deposit/Withdraw 공용 응답)
      query/
        AccountQuery.kt              ← 읽기 전용 Query 인터페이스 (root cqrs-pattern.md 네이밍/배치)
        GetAccountService.kt / GetAccountResult.kt
        GetTransactionsService.kt / GetTransactionsResult.kt
      event/                            ← Outbox가 드레인한 이벤트를 처리하는 Handler (harness event-placement 검사 대상)
        AccountCreatedEventHandler.kt
        MoneyDepositedEventHandler.kt
        MoneyWithdrawnEventHandler.kt
        AccountSuspendedEventHandler.kt
        AccountReactivatedEventHandler.kt
        AccountClosedEventHandler.kt
      service/                          ← 도메인 스코프 Technical Service 인터페이스 (domain-service.md)
        NotificationService.kt           ← interface (Spring 무의존) — 이메일 발송 추상화

    infrastructure/
      persistence/
        AccountJpaEntity.kt              ← domain.Account의 JPA 매핑 전용 대응물 (@Entity)
        TransactionJpaEntity.kt          ← domain.Transaction의 JPA 매핑 전용 대응물 (@Entity)
        MoneyEmbeddable.kt               ← domain.Money의 JPA 매핑 전용 대응물 (@Embeddable + data class)
        AccountMapper.kt                 ← Account ↔ AccountJpaEntity 변환 전담 (internal object)
        TransactionMapper.kt             ← Transaction ↔ TransactionJpaEntity 변환 전담 (internal object)
        AccountJpaRepository.kt          ← JpaRepository&lt;AccountJpaEntity, Long&gt; 확장
        TransactionJpaRepository.kt      ← JpaRepository&lt;TransactionJpaEntity, Long&gt; 확장
        AccountRepositoryImpl.kt         ← @Repository, AccountRepository 구현체 (Mapper로 Entity↔Domain 변환)

      notification/                    ← Technical Service 구현 (SES 연동) — account 전용, 별도 BC 아님
        NotificationServiceImpl.kt       ← @Component, SES 연동 구현체
        SesConfig.kt                     ← @Configuration, SesClient Bean
        persistence/
          SentEmail.kt                   ← @Entity (발송 이력)
          SentEmailJpaRepository.kt

    interfaces/
      rest/
        AccountController.kt             ← @RestController
        Schemas.kt                       ← Request/Response data class 모음
```

---

## Account 모듈 — root 4레이어와의 대응

| root 레이어 | 이 저장소 패키지 | 비고 |
|---|---|---|
| domain/ | `account/domain/` | 순수 Kotlin — Spring/JPA import 없음. JPA 매핑은 infrastructure로 분리 (아래 참고) |
| application/ | `account/application/{command,query,event,integrationevent}/` | Card BC 추가로 `integrationevent/`(root의 `integration-event/`에 대응 — Kotlin 패키지명은 하이픈을 쓸 수 없어 붙여 쓴다)가 생겼다. Account는 다른 BC를 동기 호출하지 않으므로 `adapter/`는 여전히 미사용 — 이는 Card BC(`card/application/adapter/`)에 있다 |
| interface/ | `account/interfaces/rest/` | root는 `interface/`(단수), 이 저장소는 Java/Kotlin 관례인 `interfaces/`(복수) 사용 |
| infrastructure/ | `account/infrastructure/persistence/` | JpaEntity/Embeddable + Mapper + Repository 구현체를 `persistence/` 하위 패키지에 배치 |

### domain/JPA 분리 — JpaEntity + Mapper

root 원칙은 "domain/은 어떤 프레임워크도 import하지 않는다"이다. 이 저장소는 이 원칙을 예외 없이 적용한다: `domain/`의 `Account`/`Transaction`/`Money`는 `jakarta.persistence` 애노테이션을 전혀 붙이지 않는 순수 Kotlin 클래스(`data class`, null-safety)이고, JPA 매핑은 `infrastructure/persistence/`의 전용 대응물이 전담한다.

| domain (순수) | infrastructure/persistence (JPA 매핑) | 역할 |
|---|---|---|
| `Account` (Aggregate Root) | `AccountJpaEntity` (`@Entity`) | `@Id`/`@Column`/`@Embedded`/`@Enumerated` 컬럼 매핑 |
| `Transaction` (하위 Entity) | `TransactionJpaEntity` (`@Entity`) | 상동 (생성 후 불변이라 insert 전용) |
| `Money` (Value Object) | `MoneyEmbeddable` (`@Embeddable`) | `amount`/`currency` 임베더블 컬럼 매핑 |

- **Mapper가 변환을 전담한다.** `AccountMapper`/`TransactionMapper`(`internal object`)가 Entity ↔ Domain 양방향 변환을 담당하며, `AccountRepositoryImpl` 안에서만 쓰인다. Domain/Application 레이어는 JpaEntity·Mapper의 존재조차 모른다.
- **복원은 `reconstitute()`로.** Mapper의 `toDomain()`은 도메인 팩토리 `Account.reconstitute(...)`/`Transaction.reconstitute(...)`를 호출한다 — `create()`와 달리 도메인 이벤트를 만들지 않고, 이미 커밋된 상태를 그대로 재구성한다.
- **저장은 PK 보존.** `AccountMapper.updateEntity(existing, account)`가 기존 행(DB 생성 `id`)의 가변 필드만 덮어써 update로 처리하고, 신규는 `toNewEntity(account)`(PK 없음)로 insert한다. `AccountRepositoryImpl.save()`가 `findByAccountId`로 기존 행 유무를 판별해 둘을 분기한다.
- **JPQL은 JpaEntity를 대상으로 한다.** `AccountRepositoryImpl`의 동적 조회는 `SELECT a FROM AccountJpaEntity a ...`로 쓰고, 결과를 `AccountMapper::toDomain`으로 매핑해 반환한다.

harness의 `domain-purity` 검사는 이제 Spring 스테레오타입(`@Service`/`@Component`/`@Repository`/`@Controller`)뿐 아니라 `domain/`의 `jakarta.persistence` import까지 FAIL 처리한다 — 과거처럼 JPA 애노테이션을 domain 클래스에 되붙이는 회귀가 자동으로 잡힌다. (이 분리는 java-springboot의 동일 구조 — `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper` — 와 정확히 대응한다.)

---

## notification — Account 도메인 내부 Technical Service (별도 최상위 패키지 아님)

이메일 발송은 별도 Bounded Context가 아니라, [domain-service.md](../../../../docs/architecture/domain-service.md)가 정의하는 **Technical Service**(암복호화, 파일 스토리지, 이메일 발송 등 기술 인프라 추상화)의 실제 구현이다. Account BC만 이를 사용하므로, `account/`와 형제인 최상위 `notification/` 패키지가 아니라 **`account/` 내부**에 배치한다 — `application/service/NotificationService.kt`(인터페이스)와 `infrastructure/notification/`(구현체 + 발송 이력)이 그것이다. (java-springboot·nestjs도 동일하게 도메인 내부 배치를 택했다.)

**domain/이 없는 이유**: Technical Service는 비즈니스 불변식을 갖는 Aggregate가 아니라 순수 기술 기능(SES 호출)이므로 Aggregate Root, Repository가 필요 없다. 대신 `SentEmail`(발송 이력)이라는 자체 Entity와 JPA Repository를 `infrastructure/notification/persistence/`에 둔다 — 이 이력은 도메인 규칙이 아니라 감사/디버깅 목적의 기술적 기록이라 domain 레이어로 승격하지 않았다.

```kotlin
// account/application/service/NotificationService.kt — 인터페이스, Spring 무의존
interface NotificationService {
    fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String)
}
```

```kotlin
// account/infrastructure/notification/NotificationServiceImpl.kt — 구현체, AWS SES SDK 사용
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    private val sesProperties: SesProperties,   // config/SesProperties.kt, config.md 참조 — @Value 아님
) : NotificationService { /* ... */ }
```

`account/application/event/`의 각 Handler(`AccountCreatedEventHandler` 등)가 이 인터페이스만 의존하고, 실제 SES 호출 방식(SDK 버전, 자격 증명 방식)은 전혀 알지 못한다 — Technical Service 패턴이 의도한 대로 Application 레이어가 인프라 세부사항으로부터 격리된다.

---

## 파일 네이밍 규칙 — Kotlin/Java 관례 (root의 kebab-case 아님)

root 문서는 kebab-case 파일명(`order-repository.ts`)을 규정하지만, Kotlin/Java 생태계는 **파일명 = 최상위 public 클래스명(PascalCase)**이 표준 관례다(`kotlinc`/IntelliJ 모두 이를 전제).

| 종류 | 위치 | 파일명 패턴 | 예시 |
|------|------|------------|------|
| Aggregate Root | `domain/` | `<AggregateRoot>.kt` | `Account.kt` |
| Value Object | `domain/` | `<ValueObject>.kt` | `Money.kt` |
| Domain Event | `domain/` | `<PascalCase 과거형>Event.kt` | `AccountCreatedEvent.kt` |
| Repository 인터페이스 | `domain/` | `<Aggregate>Repository.kt` | `AccountRepository.kt` |
| Repository 구현체 | `infrastructure/persistence/` | `<Aggregate>RepositoryImpl.kt` | `AccountRepositoryImpl.kt` |
| Command Service | `application/command/` | `<Verb><Noun>Service.kt` | `CreateAccountService.kt` |
| Command | `application/command/` | `<Verb><Noun>Command.kt` | `CreateAccountCommand.kt` |
| Query Service | `application/query/` | `<Verb><Noun>Service.kt` | `GetAccountService.kt` |
| Result | `application/query/` 또는 `command/` | `<Verb><Noun>Result.kt` | `GetAccountResult.kt` |
| Event Handler | `application/event/` | `<DomainEvent>Handler.kt` | `AccountCreatedEventHandler.kt` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller.kt` | `AccountController.kt` |
| 에러 계층 | `domain/` | `<Domain>Exception.kt` (sealed class + 하위 클래스 한 파일) | `AccountException.kt` |

harness의 `file-naming` 검사(`^[A-Z][A-Za-z0-9]*$`)가 이 규칙을 실제로 강제한다.

---

## 공용 인프라 배치

root가 규정하는 공용 패키지는 대부분 프로젝트 루트(`com.example.accountservice.*`, 각 BC 패키지 밖)에 자리 잡고 있다 — Account/Card 두 BC가 이를 함께 공유한다.

| 패키지 | 용도 | 현재 상태 |
|---|---|---|
| `common/` | Correlation ID Filter, 요청 로깅 Interceptor, ID 생성 유틸(`GenerateId.kt`) 등 | **있음** — [cross-cutting-concerns.md](cross-cutting-concerns.md), [aggregate-id.md](aggregate-id.md) 참조 |
| `config/` | `@ConfigurationProperties` data class 모음(`AwsProperties`, `SesProperties`) | **있음** — [config.md](config.md) 참조 |
| `auth/` | JWT 발급/검증, Spring Security 설정 | **있음** — [authentication.md](authentication.md) 참조 |
| `secret/` | AWS Secrets Manager 연동, TTL 캐시 | **있음** — [secret-manager.md](secret-manager.md) 참조 |
| `outbox/` | Domain Event Outbox, Writer, Poller, Consumer | **있음** — [domain-events.md](domain-events.md) 참조 |

두 번째 BC가 추가되거나 규모가 더 커질 때는 각 패키지 안에서 하위 구조를 더 세분화하면 된다 — 현재는 [shared-modules.md](shared-modules.md)가 정의하는 배치를 그대로 따른다.

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, 역할
- [repository-pattern.md](repository-pattern.md) — Repository 배치와 네이밍
- [tactical-ddd.md](tactical-ddd.md) — domain/ 내부 설계
