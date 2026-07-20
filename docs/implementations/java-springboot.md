# Java Spring Boot 구현체

## 개요

[Spring Boot](https://spring.io/projects/spring-boot)는 Java 진영의 사실상 표준 백엔드 프레임워크로, Spring Data JPA·선언적 트랜잭션(`@Transactional`)·DI 컨테이너를 내장한다.
이 플레이북의 원칙을 Spring Boot로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/java-springboot/`에 있다.

**→ [implementations/java-springboot/CLAUDE.md](../../implementations/java-springboot/CLAUDE.md)** — Spring Boot 구현 상세 가이드 진입점 (키워드 → 문서 인덱스)
**→ [implementations/java-springboot/docs/architecture/](../../implementations/java-springboot/docs/architecture/)** — root의 21개 아키텍처 주제 각각에 대한 Java 전용 상세 문서 (21개 파일)
**→ [implementations/java-springboot/examples/](../../implementations/java-springboot/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + SES 알림, Spring Boot 3.3 / Java 21)
**→ [implementations/java-springboot/harness/](../../implementations/java-springboot/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 문서는 root 원칙 문서와 Java Spring Boot 구현 문서 간의 커버리지 매핑이다. root `docs/architecture/`의 21개 파일 각각에 대응하는 Java 전용 문서가 `docs/architecture/`에 있다. **문서화 수준과 `examples/`의 실제 코드가 그 원칙을 따르는지는 별개다** — 각 문서가 남은 코드 gap을 "알려진 gap"으로 명시하며, 아래 표의 비고 컬럼에도 반복해 강조한다.

---

## Java Spring Boot 구현 커버리지

| 원칙 문서 (루트, 공용) | 상태 | Java Spring Boot 구현 문서/코드 | 비고 |
|---|---|---|---|
| [tactical-ddd.md](../architecture/tactical-ddd.md) | **Covered** | `docs/architecture/tactical-ddd.md`, `examples/.../account/domain/Account.java`, `Money.java`, `Transaction.java` | `protected Account()` + `static create()` 정적 팩토리, `record` Value Object(`Money`), 과거형 `record` Domain Event. 설계 자체는 문서·코드 모두 일치. |
| [layer-architecture.md](../architecture/layer-architecture.md) | **Covered** | `docs/architecture/layer-architecture.md` | 의존 방향, Command/Query Service, `@Transactional` 전파를 문서화. `Account`/`Transaction`/`Money`는 순수 도메인 객체이고, JPA 매핑은 `AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable`(infrastructure) + `AccountMapper`/`TransactionMapper`로 분리되어 있다 — root의 "Domain은 ORM 무의존" 규칙과 일치한다. |
| [directory-structure.md](../architecture/directory-structure.md) | **Covered** | `docs/architecture/directory-structure.md` | Account 모듈 실제 트리 전체를 문서화(notification은 account 내부 Technical Service로 배치됨). `interfaces/rest/`(복수형, Java 예약어 `interface` 회피)를 root의 `interface/`(단수)와 명시적으로 대조. |
| [repository-pattern.md](../architecture/repository-pattern.md) | **Covered** | `docs/architecture/repository-pattern.md` | 올바른 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 컨벤션과 `take:1` 단건 조회 패턴을 정의. `AccountRepository`에 `delete(String accountId)`(soft delete)가 있어 root의 "delete 메서드 필요" 원칙과 일치한다. 조회/저장 메서드는 `findAccounts`(목록+count를 `AccountsWithCount`로 함께 반환)/`saveAccount` 하나로 통일돼 있다 — 단건 조회는 `findAccounts`를 `take:1`로 호출한 뒤 `Stream.findFirst()`로 처리한다. 하위 Entity `Transaction`도 `findTransactions` 하나로 동일한 패턴을 따른다. |
| [aggregate-id.md](../architecture/aggregate-id.md) | **Covered** | `docs/architecture/aggregate-id.md`, `examples/.../common/IdGenerator.java` | 32자리 hex, 하이픈 제거 규칙을 정의. `Account.create()`/`Transaction.create()`/`SentEmail.create()` 모두 `IdGenerator.generate()`(하이픈 없는 32자리 hex)를 쓴다. |
| [domain-events.md](../architecture/domain-events.md) | **Covered** | `docs/architecture/domain-events.md`, `examples/.../outbox/OutboxEvent.java`, `OutboxWriter.java`, `OutboxPoller.java`, `OutboxConsumer.java`, `examples/.../account/application/event/*EventHandler.java` | Outbox 테이블(JPA `@Entity`) + `AccountRepositoryImpl.save()`가 Aggregate와 같은 트랜잭션에서 이벤트를 저장하는 부분은 그대로다. 드레인 경로는 동기 호출에서 완전히 전환됐다 — `OutboxPoller`(`@Scheduled(fixedDelay=1000)`)가 Outbox 테이블을 SQS로 발행하고, `OutboxConsumer`(SQS long polling, `SmartLifecycle`)가 수신해 핸들러를 실행한다. root/nestjs와 동일한 메시지 큐 경유 비동기 경로이며, Command Service는 `OutboxRelay`/`OutboxPoller`/`OutboxConsumer`를 전혀 참조하지 않는다(harness `outbox-drain-order` 규칙이 회귀 방지). |
| [error-handling.md](../architecture/error-handling.md) | **Covered** | `docs/architecture/error-handling.md` | `AccountException.ErrorCode` enum, `ErrorResponse{statusCode,code,message,error}` 4필드 구조, `@RestControllerAdvice` 전역 Validation 처리를 정의. `AccountController.handleAccountException`이 `ErrorResponse.of(status, code, message)`로 4필드를 채운다. 계층별 예외 throw/변환 분리도 문서와 일치. |
| [persistence.md](../architecture/persistence.md) | **Covered** | `docs/architecture/persistence.md` | `@Transactional`/`REQUIRES_NEW`(`NotificationServiceImpl`에서 사용) 전파, Flyway 마이그레이션, soft delete 배선을 정의. 마이그레이션은 Flyway(`db/migration/`) + `ddl-auto: validate`로 처리된다. `Account.delete()`(도메인, CLOSED 상태만 허용) + `AccountRepository.delete()`(soft delete) + `DeleteAccountService`(application, 소유권 검증 후 호출)로 soft delete가 배선되어 있다. |
| [testing.md](../architecture/testing.md) | **Covered** | `docs/architecture/testing.md`, `examples/.../AccountTest.java`, `MoneyTest.java`, `CreateAccountServiceTest.java`, `DepositServiceTest.java` | Domain(순수 Java)/Application(Mockito)/E2E(Testcontainers) 3계층이 모두 실제 코드로 존재한다. |
| [authentication.md](../architecture/authentication.md) | **Covered** | `docs/architecture/authentication.md`, `examples/.../config/SecurityConfig.java`, `AccountController.java` | JWT 발급(`SignInService`)/검증(Spring Security `oauth2ResourceServer().jwt()`)/`SecurityConfig` 화이트리스트 패턴을 정의. `AccountController`의 모든 엔드포인트가 `Authentication` 파라미터로 인증을 요구한다. `SignInService`는 `Credential`(bcrypt 해시) 저장소와 대조해 실제 비밀번호를 검증한 뒤에만 토큰을 발급하고, `POST /auth/sign-up`으로 가입을 처리한다. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | **Covered** | `docs/architecture/cqrs-pattern.md` | 경량 CQRS(Command/Query Service 분리)를 문서화하고, `AccountQuery` 인터페이스(root 컨벤션과 일치하는 명명)와 Handler 기반 CQRS 전환 기준을 명시. `GetAccountService`/`GetTransactionsService` 모두 쓰기용 `AccountRepository`가 아니라 좁은 `AccountQuery`(application/query)에 의존한다 — `AccountRepositoryImpl`이 두 인터페이스를 모두 구현해 DI를 분기하고, harness의 `cqrs-query-purity` 규칙이 회귀를 방지한다. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | **Covered** | `docs/architecture/cross-cutting-concerns.md`, `examples/.../common/web/CorrelationIdFilter.java`, `RequestLoggingInterceptor.java` | `CorrelationIdFilter`(MDC), `RequestLoggingInterceptor` 등 Spring MVC 파이프라인 매핑을 정의. Correlation ID 주입과 요청 로깅 Interceptor 모두 구현되어 있다. |
| [config.md](../architecture/config.md) | **Covered** | `docs/architecture/config.md`, `examples/.../config/AwsProperties.java`, `SesProperties.java` | `@ConfigurationProperties`(record) + `@Validated`로 Fail-fast를 얻는 방법, 관심사별 설정 파일 분리를 정의. `AwsProperties`/`SesProperties`의 모든 필드(`region`/`accessKeyId`/`secretAccessKey`/`senderEmail`)에 `@NotBlank`가 붙어 있고, `application-prod.yml`이 운영 프로필에서 AWS 자격증명 기본값을 추가로 제거해 fail-fast를 이중으로 강제한다. 세분화된 `application-database.yml`/`application-aws.yml`/`application-jwt.yml`/`application-local.yml` 분리는 이 저장소 규모에 불필요한 복잡도로 판단해 2-파일 구성(`application.yml` + `application-prod.yml`)을 최종 구조로 확정했다. |
| [container.md](../architecture/container.md) | **Covered** | `docs/architecture/container.md`, `examples/Dockerfile` | Layered JAR 기반 Gradle 멀티스테이지 Dockerfile, JRE 베이스, Actuator 헬스체크를 정의. 멀티스테이지 Dockerfile(Layered JAR, JRE 베이스, non-root 사용자)과 Actuator 헬스체크(`spring-boot-starter-actuator` + `management.endpoint.health.probes.enabled`) 모두 적용되어 있다. |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | **Covered** | `docs/architecture/graceful-shutdown.md` | `server.shutdown: graceful`, Actuator liveness/readiness, `AvailabilityChangeEvent` 자동 연동을 정의. `application.yml`에 `server.shutdown: graceful` + `timeout-per-shutdown-phase: 30s`, Actuator liveness/readiness probes가 설정되어 있다. |
| [observability.md](../architecture/observability.md) | **Covered** | `docs/architecture/observability.md` | 구조화 로깅(Logback JSON 인코더 + `StructuredArguments.kv`), MDC 기반 Correlation ID, Actuator/Micrometer 메트릭을 정의. `NotificationServiceImpl`/`OutboxPoller`/`OutboxConsumer`/`AccountController` 모두 `StructuredArguments.kv(...)`로 구조화 로깅을 하고, `logback-spring.xml`(로컬 평문/운영 JSON 분기)이 존재한다. Actuator 헬스체크(container.md/graceful-shutdown.md 참고)와 Micrometer-Prometheus 메트릭(`io.micrometer:micrometer-registry-prometheus` + `GET /actuator/prometheus`)이 모두 구현되어 있다. Micrometer Tracing(OpenTelemetry 연동)은 이 저장소 규모에서 아직 필요하지 않다고 판단해 도입하지 않았다. |
| [scheduling.md](../architecture/scheduling.md) | **Covered** | `docs/architecture/scheduling.md`, `examples/.../outbox/OutboxPoller.java` | `@Scheduled`/`@EnableScheduling`, Task Outbox, FIFO dedup·ShedLock 기반 다중 인스턴스 안전성을 정의. `@Scheduled(fixedDelay=1000)`이 실제로 `OutboxPoller`에 쓰였다 — domain-events.md가 이 문서가 예고했던 SQS 기반 목표 설계(DLQ, at-least-once 멱등성)를 실제 코드로 구현했다. 배치 유스케이스(Task Outbox/ShedLock 대상)는 여전히 없다. |
| [secret-manager.md](../architecture/secret-manager.md) | **Covered** | `docs/architecture/secret-manager.md`, `examples/.../common/service/SecretService.java`, `common/infrastructure/SecretServiceImpl.java`, `common/config/SecretsEnvironmentPostProcessor.java` | AWS Secrets Manager + TTL 캐시(`ConcurrentHashMap`), `EnvironmentPostProcessor` 기반 기동 시 주입을 정의. `SecretService`/`SecretServiceImpl`(TTL 캐시 포함)과 `SecretsEnvironmentPostProcessor`(운영 프로필에서 `app/jwt` 시크릿을 조회해 `jwt.secret`을 주입)가 구현되어 있다. `SesConfig`는 SES 자격증명 자체를 `AwsProperties`(환경 변수)로만 받는다(의도적 — SES는 IAM 기반이 일반적). |
| [api-response.md](../architecture/api-response.md) | **Covered** | `docs/architecture/api-response.md`, `examples/.../GetAccountResult.java`, `GetTransactionsResult.java` | `page`/`take` 쿼리 파라미터, `{ transactions: [...], count }` 목록 응답, 단건 무래퍼 반환이 정의와 일치한다. Repository 반환 형식은 repository-pattern.md로 교차 참조. |
| [local-dev.md](../architecture/local-dev.md) | **Covered** | `docs/architecture/local-dev.md`, `examples/docker-compose.yml`, `examples/localstack/init-ses.sh`, `examples/localstack/init-sqs.sh` | Postgres + LocalStack(SES/Secrets Manager/SQS) healthcheck 구성, 버전 고정, 초기화 스크립트가 정의와 일치한다. `init-sqs.sh`가 `OutboxPoller`/`OutboxConsumer`용 `domain-events`/`domain-events-dlq` 큐를 만든다(domain-events.md 참고). |
| [file-storage.md](../architecture/file-storage.md) | **Covered** | `docs/architecture/file-storage.md` | `S3Presigner` 기반 Presigned URL 패턴, `account/infrastructure/notification/`의 Technical Service 구조를 템플릿으로 재사용하는 방법을 정의. Account 도메인에 첨부파일 유스케이스 자체가 없어 코드 대응은 없다(순수 신규 문서). |
| **[domain-service.md](../architecture/domain-service.md)** | **Covered** | Java 전용 대응 문서는 [`domain-service.md`](../../implementations/java-springboot/docs/architecture/domain-service.md), `examples/.../payment/domain/RefundEligibilityService.java`, `RefundDecision.java`, `application/command/RequestRefundService.java` | Payment BC(Payment/Refund 두 Aggregate)가 추가되며 `RefundEligibilityService`가 실제로 두 Aggregate를 조율하는 Domain Service 예시로 코드에 등장했다 — 프레임워크 애노테이션 없는 순수 클래스, `RequestRefundService`가 `new`로 직접 인스턴스화. Technical Service 예시(`NotificationService`/`NotificationServiceImpl`)도 계속 유효하다. |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | **Covered** | Java 전용 대응 문서는 [`cross-domain.md`](../../implementations/java-springboot/docs/architecture/cross-domain.md)(아래 "Java Spring Boot 전용" 절) | Account/Card 두 BC가 실제로 존재해 동기 Adapter(`card/application/adapter/AccountAdapter` + `card/infrastructure/AccountAdapterImpl`)와 비동기 Integration Event(`account.suspended.v1`/`account.closed.v1`) 양쪽 모두 실제 코드로 구현되어 있다. |
| **[strategic-ddd.md](../architecture/strategic-ddd.md)** | **Missing** | — | Subdomain 분류, BC 식별, Context Map에 대한 Java 관점의 전용 문서 없음. |

**요약**: 24개 root 아키텍처 문서 중 **Covered 23 / Missing 1**. `notification`은 최상위 공유 모듈이 아니라 `account` 도메인 내부(Technical Service)에 배치되어 있다.

---

## Java Spring Boot 전용, 대응 root 문서 없음 (NestJS 대비 보너스 문서 6종)

`implementations/nestjs/docs/architecture/`에는 root 21개 주제 어디에도 대응하지 않지만 실무 가치가 있는 보너스 문서 6개(`bootstrap.md`/`cross-domain.md`/`design-principles.md`/`module-pattern.md`/`rate-limiting.md`/`shared-modules.md`)가 있다. 같은 6개 주제를 Java Spring Boot 관용으로 다시 쓴 문서가 있다 — NestJS 문서를 그대로 옮긴 것이 아니라 Spring 고유 메커니즘(생성자 주입, `@ConfigurationProperties`, `@Bean`/`@Configuration`, Resilience4j 등) 기준으로 작성했다.

| 문서 | 내용 |
|---|---|
| `docs/architecture/bootstrap.md` | `AccountServiceApplication`의 `@SpringBootApplication`/`SpringApplication.run()` 부트스트랩 순서, `application.yml` 로딩 순서, `@RestControllerAdvice` 전역 예외 처리 배선(rate limit + Validation 실패 둘 다, error-handling.md 교차 참조). Actuator는 도입되어 있다 — springdoc/CORS 도입 가이드만 여전히 forward-looking |
| `docs/architecture/cross-domain.md` | 도메인 간 동기 호출 Adapter 패턴의 Java/Spring 구현 예시 — `application/adapter/` 인터페이스 + `infrastructure/` 구현체. Account/Card 두 BC가 실제로 존재해 동기 Adapter와 비동기 Integration Event 양쪽 모두 실제 코드로 다룬다 |
| `docs/architecture/design-principles.md` | 이 저장소의 핵심 설계 원칙 13개를 압축한 TL;DR 인덱스 |
| `docs/architecture/module-pattern.md` | Spring DI 컨테이너 메커니즘(스테레오타입 애노테이션, 생성자 주입, `@Bean`/`@Configuration`, `@Profile`)과 NestJS `@Module`의 근본적 차이(모듈 경계가 컴파일 타임에 강제되지 않음), 순환 의존 시 `@Lazy` vs BC 경계 재설계 |
| `docs/architecture/rate-limiting.md` | Resilience4j `RateLimiter` 기반 `Filter` 구현 예시. `build.gradle`에 `resilience4j-spring-boot3` 의존성이 있고 `RateLimitFilter`가 모든 비-actuator 요청에 적용된다 |
| `docs/architecture/shared-modules.md` | `common/`/`config/`/`database/`/`outbox/`/`auth/` 공유 코드 배치 컨벤션. 현재 최상위 도메인 패키지가 `account` 하나뿐이라(notification은 account 내부 Technical Service) 공유 패키지가 실재하지 않음을 확인하고, 새로 추가할 때의 권장 배치를 제시 |

---

## harness.sh 검증 범위

`implementations/java-springboot/harness/harness.sh`는 8개 섹션(file-naming, repository-annotation, service-annotation, domain-purity, controller-placement, package-structure, shared-infra, event-placement)으로 구성되며, 파일 배치·어노테이션 위치·디렉토리 존재 여부만 검사한다. 다음은 harness가 잡아주지 않는 한계다:

- `package-structure`는 디렉토리 존재 여부만 확인 — `cqrs-pattern.md`가 명시한 "Query Service가 Repository를 직접 쓰면 안 된다" 위반은 이 규칙만으로는 감지하지 못한다(harness의 `cqrs-query-purity` 규칙이 별도로 이를 검사한다).
- `domain-purity`는 Spring 스테레오타입 애노테이션만 금지하고 **JPA 애노테이션은 검사 대상이 아니다** — `Account`/`Transaction`/`Money`가 순수 도메인으로 분리되어 있어도, 누군가 다시 `@Entity`를 domain 클래스에 붙이는 회귀가 생기면 이 harness는 잡아내지 못한다.
- Repository 메서드 네이밍, soft delete 실제 동작, 테스트 레이어 구성 등은 harness에 해당 검사 자체가 없다.

문서가 "Covered"로 표시하는 항목이라도, harness가 그 내용을 실질적으로 강제하는 경우는 많지 않다 — 검증은 대부분 구조적 배치 규칙에 머물러 있다.

---

## Java Spring Boot 선택 이유

- Spring Data JPA(`JpaRepository`)와 선언적 `@Transactional`/`@Transactional(readOnly = true)`이 Repository 패턴과 트랜잭션 전파를 프레임워크 차원에서 기본 제공한다.
- `@Transactional(propagation = Propagation.REQUIRES_NEW)`처럼 트랜잭션 전파 속성을 선언적으로 제어할 수 있어, 알림 발송 실패가 원본 커맨드 트랜잭션에 전이되지 않도록 격리하는 패턴(`NotificationServiceImpl`)을 어노테이션만으로 구현할 수 있다.
- Java record(JDK 16+)로 Value Object·Command·Result·Query 객체를 간결한 불변 객체로 표현할 수 있다(`Money`, `AccountFindQuery`, `CreateAccountCommand`).
- Lombok(`@RequiredArgsConstructor`)으로 생성자 주입 보일러플레이트를 줄여, DI를 사용하는 Application/Infrastructure 레이어 코드가 상대적으로 간결하다.
- Spring Boot Actuator + Micrometer가 헬스체크/메트릭을 프레임워크 차원에서 기본 지원해, graceful shutdown·observability 도입 시 별도 라이브러리 조합이 거의 필요 없다.

---

### 관련 문서

- [implementations/kotlin-springboot/](../../implementations/kotlin-springboot/) — 동일 아키텍처의 Kotlin 버전 (Spring 메커니즘은 유사하지만 언어 관용구는 다름: null-safety, `data class`, `sealed class`)
- [implementations/nestjs/docs/architecture/](../../implementations/nestjs/docs/architecture/) — 21개 파일 구조의 기준이 된 참고 구현
