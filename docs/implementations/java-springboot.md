# Java Spring Boot 구현체

## 개요

[Spring Boot](https://spring.io/projects/spring-boot)는 Java 진영의 사실상 표준 백엔드 프레임워크로, Spring Data JPA·선언적 트랜잭션(`@Transactional`)·DI 컨테이너를 내장한다.
이 플레이북의 원칙을 Spring Boot로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/java-springboot/`에 있다.

**→ [implementations/java-springboot/CLAUDE.md](../../implementations/java-springboot/CLAUDE.md)** — Spring Boot 구현 가이드 진입점
**→ [implementations/java-springboot/docs/guide.md](../../implementations/java-springboot/docs/guide.md)** — 구현 상세 가이드 (단일 문서)
**→ [implementations/java-springboot/examples/](../../implementations/java-springboot/examples/)** — Account 도메인 전체 구현 예시 (Spring Boot 3.3 / Java 21)
**→ [implementations/java-springboot/harness/](../../implementations/java-springboot/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

> **참고 — guide.md와 examples/의 도메인 예시 불일치**: `docs/guide.md`의 모든 코드 예시는 여전히 `Order`/`OrderItem`/`OrderRepository` 등 과거 Order 도메인을 사용한다. 반면 `examples/`는 이미 Account 도메인(계좌 개설/입출금/정지/재개/종료)으로 전환되어 있다. 즉 guide.md 본문과 실제 실행 가능한 예제 코드가 서로 다른 도메인을 다루고 있어, 아래 커버리지 판단은 **guide.md 본문(텍스트) 따로, examples/ 코드 따로** 확인한 결과다. guide.md 자체의 Order→Account 갱신은 이 문서의 범위 밖이다 (별도 이슈로 추적 필요).

---

## 커버리지 요약

루트 원칙 문서 24개 중 **Covered 4 / Thin 9 / Missing 11 / N/A 0**.

nestjs.md(참고 구현체)와 비교하면 Java/Spring Boot 쪽 guide.md는 패키지 구조·Repository·Aggregate·CQRS(경량)·에러 처리 등 핵심 전술 패턴만 다루고, 운영/인프라/횡단 관심사 계열 문서(config, secret-manager, observability, graceful-shutdown, container, scheduling, authentication, cross-cutting-concerns 등)는 guide.md 본문에 전혀 등장하지 않는다. `examples/`에도 해당 코드가 없어 Missing으로 분류했다.

---

## 원칙 문서별 커버리지

| 원칙 문서 (루트, 공용) | 상태 | Java/Spring 구현 위치 | 비고 |
|---|---|---|---|
| [aggregate-id.md](../architecture/aggregate-id.md) | Thin | `examples/.../account/domain/Account.java` `create()` | `UUID.randomUUID().toString()`로 서버 측 domain 팩토리에서 생성하는 위치는 맞으나, 하이픈을 제거하지 않아 root의 "32자리 hex, 하이픈 없음" 규칙과 다르다. guide.md 본문에는 ID 생성 관련 언급이 아예 없다. |
| [api-response.md](../architecture/api-response.md) | Covered | `examples/.../account/application/query/GetTransactionsResult.java`, `AccountController` | `page`/`take` 쿼리 파라미터, `{ transactions: [...], count }` 형태의 목록 응답, 단건은 래퍼 없이 직접 반환 — 원칙과 일치. 다만 guide.md 본문에는 이 주제에 대한 서술이 없고 examples/ 코드로만 확인된다. |
| [authentication.md](../architecture/authentication.md) | Missing | `AccountController`의 `@RequestHeader("X-User-Id")` | 토큰 발급·검증 절차 자체가 없다. 클라이언트가 보낸 `X-User-Id` 헤더를 그대로 신뢰하는 방식으로, JWT/Guard/Filter 등 root가 요구하는 인증 메커니즘이 전혀 구현되어 있지 않다. guide.md도 언급 없음. |
| [config.md](../architecture/config.md) | Missing | `examples/src/main/resources/application.yml` | 기동 시 Fail-fast 검증(`@ConfigurationProperties` + `@Validated` 등) 없음, 관심사별 설정 파일 분리 없음(단일 `application.yml`), AWS 자격증명은 `test`/`test` 기본값을 프로덕션 분기 없이 사용. guide.md 미언급. |
| [container.md](../architecture/container.md) | Missing | — | `examples/` 어디에도 Dockerfile/.dockerignore가 없다. guide.md도 컨테이너 이미지 관련 서술이 없음. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | Thin | `examples/.../account/application/{command,query}/*Service.java` | Command Service(`@Transactional`)/Query Service(`@Transactional(readOnly = true)`)로 클래스는 분리했으나, `GetAccountService`/`GetTransactionsService`가 별도 Query 인터페이스 없이 **쓰기용 `AccountRepository`를 그대로 주입**받는다. cqrs-pattern.md와 layer-architecture.md가 명시한 "Query Service는 Query 인터페이스만 사용" 규칙 위반. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | Missing | — | Correlation ID 주입, 요청 파이프라인(Middleware/Guard/Pipe/Interceptor 대응) 어디에도 없음. `@ExceptionHandler`만 존재(이는 error-handling.md 영역). guide.md 미언급. |
| [cross-domain-communication.md](../architecture/cross-domain-communication.md) | Missing | `examples/.../account/application/event/AccountNotificationListener.java` | account → notification 호출이 존재하지만 Adapter(ACL)나 Integration Event 같은 BC 간 통신 패턴이 아니라, 같은 프로세스 내 동기 `ApplicationEventPublisher` + 직접 인터페이스 호출로만 이루어진다. BC 경계·ACL 개념이 코드/문서 어디에도 명시되어 있지 않음. |
| [directory-structure.md](../architecture/directory-structure.md) | Covered | `docs/guide.md` "패키지 구조", "파일명·패키지 네이밍" 섹션 | domain/application/infrastructure/interfaces 4레이어 및 command/query 하위 패키지 구조, PascalCase/camelCase 네이밍이 그대로 대응된다. `interface/`가 아닌 `interfaces/`(복수형)를 쓰는 것은 Java 예약어 `interface`와의 충돌을 피하기 위한 합리적인 언어별 조정으로 보인다. |
| [domain-events.md](../architecture/domain-events.md) | Thin | `docs/guide.md`에는 없음. `examples/.../account/domain/Account.java`(이벤트 수집) + `AccountNotificationListener` + `CreateAccountService` 등(`ApplicationEventPublisher::publishEvent`) | Aggregate 내부에서 이벤트를 수집(`pullDomainEvents`)하는 패턴은 맞지만, Outbox 테이블·메시지 큐·at-least-once 전달·멱등성·Integration Event 변환이 전혀 없다. 완전히 동기 in-process 발행(`@EventListener`)뿐이며, 커밋 전에 이벤트가 발행되므로(트랜잭션 완료 전 발행) root가 강조하는 "Aggregate 저장과 이벤트가 같은 트랜잭션으로 확정되거나 함께 롤백"이라는 원자성 보장도 없다. `implementations/java-springboot/CLAUDE.md`에 "동기 발행"이라는 선택이 명시는 되어 있으나 트레이드오프에 대한 설명은 없다. |
| [domain-service.md](../architecture/domain-service.md) | Thin | `examples/.../notification/application/service/NotificationService.java` (인터페이스) + `.../notification/infrastructure/NotificationServiceImpl.java` (구현체) | Technical Service 패턴(인터페이스는 application에, 구현체는 infrastructure에 두고 SDK 의존은 구현체만)은 정확히 재현되어 있다. 다만 이 개념이 guide.md 본문에 전혀 설명되지 않고, 여러 Aggregate를 조율하는 "Domain Service" 예시는 코드에 없다(Account 예제 규모상 필요 없었을 수 있음). |
| [error-handling.md](../architecture/error-handling.md) | Thin | `docs/guide.md` "에러 처리" 섹션, `examples/.../account/domain/AccountException.java`, `AccountController.handleAccountException` | 도메인이 `AccountException(ErrorCode, message)`라는 타입화된 예외를 던지고 Controller에서만 `@ExceptionHandler`로 HTTP 상태를 매핑하는 계층 분리는 Java다운 합리적 대응이다. 다만 `ErrorResponse` 레코드가 `{ code, message }` 2개 필드뿐이라 root가 명시한 표준 응답 형식(`statusCode`, `code`, `message`, `error` 4필드)에 못 미친다. |
| [file-storage.md](../architecture/file-storage.md) | Missing | — | Presigned URL/StorageService 추상화 어디에도 없음. guide.md 미언급. (Account 예제 도메인 특성상 필요 없었을 수 있으나, 파일 첨부는 일반적으로 적용 가능한 주제라 N/A로 보지 않았다.) |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | Missing | — | `/health/live`·`/health/ready` 없음(Actuator 의존성조차 `build.gradle`에 없음), SIGTERM/`server.shutdown=graceful` 설정 없음. guide.md 미언급. |
| [layer-architecture.md](../architecture/layer-architecture.md) | Thin | `docs/guide.md` 전반, `examples/.../account/domain/Account.java` | 레이어 분리와 의존 방향 자체는 준수하지만 두 가지 실질적 위반이 있다: (1) Aggregate Root(`Account`)가 `@Entity`/`@Id`/`@Column`/`@Embedded` 등 JPA 어노테이션을 직접 가진 순수 ORM 엔티티로, root의 "Domain 레이어는 ORM에도 의존하지 않는다" 규칙과 충돌한다. (2) Query Service가 별도 Query 인터페이스 없이 Repository를 직접 사용한다(cqrs-pattern.md 행 참고). guide.md는 이 두 트레이드오프를 언급하거나 정당화하지 않는다. |
| [local-dev.md](../architecture/local-dev.md) | Thin | `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | Postgres + LocalStack(SES) 구성과 healthcheck는 원칙대로 되어 있다. 다만 `profiles: [app]`로 앱 서비스를 분리하는 패턴이 없고(compose에 앱 서비스 정의 자체가 없음), `.env.development`/`.env.docker` 파일도 없다. guide.md 본문에는 로컬 개발 환경에 대한 서술이 전혀 없다. |
| [observability.md](../architecture/observability.md) | Missing | `AccountNotificationListener`, `NotificationServiceImpl`의 slf4j 로그 | `log.error("...: accountId={}, eventType={}", ...)` 형태의 일반 slf4j 로깅만 있고, JSON 구조화·snake_case 필드·Correlation ID 전파·메트릭/트레이싱은 전혀 없다(Micrometer/Actuator 의존성 자체가 없음). guide.md 미언급. |
| [persistence.md](../architecture/persistence.md) | Thin | `docs/guide.md` "Soft Delete" 섹션(`@SQLRestriction` 예시, Order 도메인 기준), `examples/`의 `deletedAt` 컬럼 | guide.md 문서상 soft delete 개념은 설명되어 있으나, 실제 `Account` 엔티티는 `deletedAt` 컬럼만 갖고 있을 뿐 이를 실제로 설정하는 delete 로직이 어디에도 없다(계좌 종료는 `status=CLOSED`로 처리하고 실제 삭제 흐름 자체가 없음). 마이그레이션 도구(Flyway/Liquibase)는 `build.gradle`에 없고 `ddl-auto: update`를 앱 설정·테스트 설정 모두에서 그대로 사용 — root가 "운영 환경에서는 반드시 마이그레이션 사용"이라 명시적으로 경고하는 부분이 정면으로 비어 있다. |
| [repository-pattern.md](../architecture/repository-pattern.md) | Thin | `docs/guide.md` "Repository 패턴" 섹션, `examples/.../account/domain/AccountRepository.java` + `infrastructure/persistence/AccountRepositoryImpl.java` | 인터페이스(domain)/구현체(infrastructure) 분리와 DI 바인딩은 정확하다. 다만 root가 강조하는 "조회는 `find<Noun>s` 하나만, 단건은 `take:1`+`.pop()` 패턴" 규칙 대신 Spring Data 관례상 전용 단건 조회 메서드(`findByAccountIdAndOwnerId`)를 별도로 둔다. `delete<Noun>` 메서드 및 하위 엔티티 cascade soft-delete도 전혀 구현되어 있지 않다. |
| [scheduling.md](../architecture/scheduling.md) | Missing | — | `@Scheduled`/Cron, TaskQueue, DLQ 어느 것도 없음. guide.md 미언급. |
| [secret-manager.md](../architecture/secret-manager.md) | Missing | `examples/.../notification/infrastructure/SesConfig.java` | AWS Secrets Manager 클라이언트나 TTL 캐시 없음. `@Value("${aws.access-key-id:test}")`처럼 자격증명을 환경 변수 기본값(`test`)으로만 처리하며 로컬/운영 분기가 없다. guide.md 미언급. |
| [strategic-ddd.md](../architecture/strategic-ddd.md) | Missing | — | Subdomain 분류, Bounded Context 정의, Context Map 어디에도 없음. `account`/`notification` 패키지 분리가 BC 경계를 암시하긴 하나 이를 설계 산출물로 명문화한 곳이 없다. guide.md 미언급. |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | Covered | `docs/guide.md` "Aggregate Root" 섹션, `examples/.../account/domain/{Account,Transaction,Money,*Event}.java` | Aggregate Root(`Account`)의 불변식 캡슐화, Entity(`Transaction`), Value Object(`Money`, record 기반 동등성), 과거형 Domain Event(`MoneyDepositedEvent` 등)가 모두 충실히 구현되어 있다. |
| [testing.md](../architecture/testing.md) | Thin | `examples/src/test/java/.../AccountControllerE2ETest.java`, `NotificationE2ETest.java` | Testcontainers 기반 E2E 테스트는 탄탄하다(Postgres+LocalStack, 실제 HTTP 요청, 케이스 커버리지 넓음). 그러나 root가 요구하는 3단 테스트 전략 중 **Domain 단위 테스트(`new Account()` 직접 테스트)와 Application 단위 테스트(Repository mock)가 전혀 없다** — E2E 계층 하나만 존재한다. guide.md에는 테스트 전략 섹션 자체가 없다. |

---

## Java/Spring 전용, 대응 root 문서 없음

`docs/guide.md`의 모든 절(패키지 구조, 네이밍, Repository 패턴, Aggregate Root, CQRS, 에러 처리, Soft Delete)은 위 표의 root 문서 중 하나에 대응된다 — 대응되는 root 문서가 아예 없는 독자적인 Java/Spring 전용 절은 확인되지 않았다. `interface`(Java 예약어) 대신 `interfaces`를 쓰는 것, Lombok(`@RequiredArgsConstructor`)이나 Java record(Value Object/Result/Command 구현)를 쓰는 것은 이미 매핑된 주제(directory-structure, tactical-ddd, api-response)의 구현 디테일이지 별도 주제는 아니다.

---

## harness.sh 검증 범위

`implementations/java-springboot/harness/harness.sh`는 8개 섹션(file-naming, repository-annotation, service-annotation, domain-purity, controller-placement, package-structure, shared-infra, event-placement)으로 구성되며, 모두 **파일 배치·어노테이션 위치·디렉토리 존재 여부**만 검사한다. 위에서 "Covered"/"Thin"으로 표시한 항목이라도 harness가 실제 동작을 검증하는 경우는 없다:

- `package-structure`는 `application/command`, `application/query` 디렉토리의 **존재 여부**만 확인한다. 정작 이번 감사에서 발견된 "Query Service가 Repository를 직접 사용한다"는 CQRS 위반은 감지하지 못한다(디렉토리는 존재하므로 통과).
- `domain-purity`는 `domain/` 파일에서 `@Service|@Component|@Repository|@Controller|@RestController`만 금지한다. `@Entity`/`@Id`/`@Column`/`@Embedded` 같은 **JPA(ORM) 어노테이션은 검사 대상이 아니다** — 이번 감사에서 확인한 `Account.java`의 domain-purity 위반(ORM 어노테이션이 domain 레이어에 존재)은 이 harness로는 절대 잡히지 않는다.
- Repository 메서드 네이밍(`find<Noun>s`/`save<Noun>`/`delete<Noun>`), 에러 응답 형식(4필드), soft delete 실제 동작, 테스트 레이어 구성(Domain/Application 단위 테스트 존재 여부) 등은 harness에 해당 검사 자체가 없다.
- `shared-infra`(outbox/task-queue)와 event-placement의 `IntegrationEvent` 규칙은 현재 examples/에 해당 패턴이 없어 사실상 skip 상태다.

즉 guide.md가 "Covered"로 다루는 주제라도 harness가 그 내용을 실질적으로 강제하는 경우는 거의 없고, 검증은 전적으로 구조적 배치 규칙에 머물러 있다.

---

## Java Spring Boot 선택 이유

- Spring Data JPA(`JpaRepository`)와 선언적 `@Transactional`/`@Transactional(readOnly = true)`이 Repository 패턴과 트랜잭션 전파를 프레임워크 차원에서 기본 제공한다 — `docs/guide.md`의 Repository/CQRS 섹션이 이를 그대로 활용한다.
- `@Transactional(propagation = Propagation.REQUIRES_NEW)`처럼 트랜잭션 전파 속성을 선언적으로 제어할 수 있어, 알림 발송 실패가 원본 커맨드 트랜잭션에 전이되지 않도록 격리하는 패턴(`NotificationServiceImpl`)을 어노테이션만으로 구현할 수 있다.
- Java record(JDK 16+)로 Value Object·Command·Result·Query 객체를 간결한 불변 객체로 표현할 수 있다(`Money`, `AccountFindQuery`, `ErrorResponse`).
- Lombok(`@RequiredArgsConstructor`)으로 생성자 주입 보일러플레이트를 줄여, DI를 사용하는 Application/Infrastructure 레이어 코드가 상대적으로 간결하다.
