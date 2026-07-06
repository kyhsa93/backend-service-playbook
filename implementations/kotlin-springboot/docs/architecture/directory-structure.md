# 디렉토리 구조 — Kotlin Spring Boot

> 프레임워크 무관 원칙은 [root directory-structure.md](../../../../docs/architecture/directory-structure.md) 참조.

## 실제 패키지 트리 — Account 도메인

`examples/src/main/kotlin/com/example/accountservice/`의 실제 구조다.

```
com.example.accountservice/
  AccountServiceApplication.kt       ← @SpringBootApplication 진입점

  account/
    domain/                          ← 프레임워크 무의존 (Spring import 없음, harness domain-purity 검사)
      Account.kt                     ← Aggregate Root (@Entity — JPA는 예외적으로 허용, 아래 참고)
      Transaction.kt                 ← 하위 Entity (@Entity)
      Money.kt                       ← Value Object (@Embeddable + data class)
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
        GetAccountService.kt / GetAccountResult.kt
        GetTransactionsService.kt / GetTransactionsResult.kt
      event/
        AccountNotificationListener.kt   ← @Component + @EventListener (harness event-placement 검사 대상)

    infrastructure/
      persistence/
        AccountJpaRepository.kt          ← Spring Data JpaRepository 확장
        TransactionJpaRepository.kt
        AccountRepositoryImpl.kt         ← @Repository, AccountRepository 구현체

    interfaces/
      rest/
        AccountController.kt             ← @RestController
        Schemas.kt                       ← Request/Response data class 모음

  notification/                      ← Technical Service 모듈 (Account BC 소속, 별도 BC 아님)
    application/
      service/
        NotificationService.kt           ← interface (Spring 무의존)
    infrastructure/
      NotificationServiceImpl.kt         ← @Component, SES 연동 구현체
      SesConfig.kt                       ← @Configuration, SesClient Bean
      persistence/
        SentEmail.kt                     ← @Entity (발송 이력)
        SentEmailJpaRepository.kt
```

---

## Account 모듈 — root 4레이어와의 대응

| root 레이어 | 이 저장소 패키지 | 비고 |
|---|---|---|
| domain/ | `account/domain/` | JPA `@Entity`/`@Embeddable`을 예외적으로 허용 (아래 참고) |
| application/ | `account/application/{command,query,event}/` | root의 `adapter/`, `integration-event/` 서브패키지는 미사용 (단일 BC 구조라 불필요) |
| interface/ | `account/interfaces/rest/` | root는 `interface/`(단수), 이 저장소는 Java/Kotlin 관례인 `interfaces/`(복수) 사용 |
| infrastructure/ | `account/infrastructure/persistence/` | Repository 구현체를 `persistence/` 하위 패키지에 배치 (Java Spring 생태계 관례) |

### domain/에 `@Entity`를 두는 것에 대해

root 원칙은 "domain/은 어떤 프레임워크도 import하지 않는다"이다. 이 저장소는 JPA 애노테이션(`@Entity`, `@Embeddable`, `@Column` 등)을 domain 클래스에 직접 붙인다 — 엄밀히는 원칙과 어긋나지만, Java/Kotlin Spring 생태계에서 굳이 별도의 Entity 클래스와 도메인 클래스를 분리하지 않는 것이 실용적 관례로 굳어져 있다(JPA 자체가 POJO/Kotlin 클래스에 애노테이션만 붙이는 방식이라 별도 프레임워크 SDK 호출이 없다). harness의 `domain-purity` 검사도 `@Service`/`@Component`/`@Repository`/`@Controller`류만 금지하고 JPA 애노테이션은 허용 대상에서 제외한다 — 이 저장소의 확립된 관례로 보면 된다. 완전한 순수성이 필요하다면 domain 클래스와 JPA Entity를 분리하고 매퍼를 두는 방식도 가능하지만, 이 예제 규모에서는 과설계로 판단해 채택하지 않았다.

---

## notification 모듈 — Technical Service 패턴의 두 번째 예시

`notification/`은 별도 Bounded Context가 아니라, [domain-service.md](../../../../docs/architecture/domain-service.md)가 정의하는 **Technical Service**(암복호화, 파일 스토리지, 이메일 발송 등 기술 인프라 추상화)의 실제 구현이다. Account BC가 "이메일을 보낸다"는 기술적 관심사를 `application/service/` 인터페이스로 추상화하고, 구현은 `infrastructure/`에 둔 것이다.

**domain/이 없는 이유**: Technical Service는 비즈니스 불변식을 갖는 Aggregate가 아니라 순수 기술 기능(SES 호출)이므로 Aggregate Root, Repository가 필요 없다. 대신 `SentEmail`(발송 이력)이라는 자체 Entity와 JPA Repository를 `infrastructure/persistence/`에 둔다 — 이 이력은 도메인 규칙이 아니라 감사/디버깅 목적의 기술적 기록이라 domain 레이어로 승격하지 않았다.

```kotlin
// notification/application/service/NotificationService.kt — 인터페이스, Spring 무의존
interface NotificationService {
    fun sendEmail(accountId: String, eventType: String, recipient: String, subject: String, body: String)
}
```

```kotlin
// notification/infrastructure/NotificationServiceImpl.kt — 구현체, AWS SES SDK 사용
@Component
class NotificationServiceImpl(
    private val sesClient: SesClient,
    private val sentEmailJpaRepository: SentEmailJpaRepository,
    @Value("\${SES_SENDER_EMAIL:...}") private val senderEmail: String,
) : NotificationService { /* ... */ }
```

`account/application/event/AccountNotificationListener`가 이 인터페이스만 의존하고, 실제 SES 호출 방식(SDK 버전, 자격 증명 방식)은 전혀 알지 못한다 — Technical Service 패턴이 의도한 대로 Application 레이어가 인프라 세부사항으로부터 격리된다.

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
| Event Listener | `application/event/` | `<Domain>NotificationListener.kt` 등 | `AccountNotificationListener.kt` |
| HTTP Controller | `interfaces/rest/` | `<Domain>Controller.kt` | `AccountController.kt` |
| 에러 계층 | `domain/` | `<Domain>Exception.kt` (sealed class + 하위 클래스 한 파일) | `AccountException.kt` |

harness의 `file-naming` 검사(`^[A-Z][A-Za-z0-9]*$`)가 이 규칙을 실제로 강제한다.

---

## 공용 인프라 배치 — 아직 없는 것들

이 예제는 단일 BC(Account) 구조라 root가 규정하는 아래 공용 패키지가 아직 없다. 도메인이 늘어나거나 비동기 처리가 필요해지면 프로젝트 루트(`com.example.accountservice.*`, `account/` 밖)에 추가한다.

| 패키지 | 용도 | 현재 상태 |
|---|---|---|
| `common/` | ID 생성 유틸(`GenerateId.kt`) 등 | 없음 — [aggregate-id.md](aggregate-id.md)에서 추가 필요성 언급 |
| `outbox/` | Domain Event Outbox, Relay, Consumer | 없음 — [domain-events.md](domain-events.md) 참조 |
| `config/` | `@ConfigurationProperties` data class 모음 | 없음 — [config.md](config.md) 참조 |

---

### 관련 문서

- [layer-architecture.md](layer-architecture.md) — 레이어 의존 방향, 역할
- [repository-pattern.md](repository-pattern.md) — Repository 배치와 네이밍
- [tactical-ddd.md](tactical-ddd.md) — domain/ 내부 설계
