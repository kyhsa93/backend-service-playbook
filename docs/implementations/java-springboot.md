# Java Spring Boot 구현체

## 개요

[Spring Boot](https://spring.io/projects/spring-boot)는 Java 진영의 사실상 표준 백엔드 프레임워크로, Spring Data JPA·선언적 트랜잭션(`@Transactional`)·DI 컨테이너를 내장한다.
이 플레이북의 원칙을 Spring Boot로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/java-springboot/`에 있다.

**→ [implementations/java-springboot/CLAUDE.md](../../implementations/java-springboot/CLAUDE.md)** — Spring Boot 구현 상세 가이드 진입점 (키워드 → 문서 인덱스)
**→ [implementations/java-springboot/docs/architecture/](../../implementations/java-springboot/docs/architecture/)** — root의 21개 아키텍처 주제 각각에 대한 Java 전용 상세 문서 (21개 파일)
**→ [implementations/java-springboot/examples/](../../implementations/java-springboot/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + SES 알림, Spring Boot 3.3 / Java 21)
**→ [implementations/java-springboot/harness/](../../implementations/java-springboot/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

이 감사는 이전 세션(단일 `docs/guide.md` 258줄, Order 도메인 기준 서술 기반)에서 크게 개편되었다. `guide.md`는 root 24개 주제 중 패키지 구조·Repository·Aggregate·CQRS(경량)·에러 처리·Soft Delete 등 일부만 다뤘고, 그마저도 모든 코드 예시가 실제로는 이미 Account로 전환된 `examples/`와 달리 여전히 Order 도메인을 사용했다. 이제 root `docs/architecture/`의 21개 파일 각각에 대응하는 Java 전용 문서가 `docs/architecture/`에 있고, `guide.md`의 실질적 내용은 모두 흡수된 뒤 (Order→Account로 예시를 갱신하며) 파일 자체는 삭제되었다. 아래 표는 그 결과를 반영한다 — **문서화 수준은 대부분 Covered로 올라갔지만, `examples/`의 실제 코드는 아직 여러 항목에서 문서가 규정하는 올바른 패턴을 따르지 않는다.** 각 문서가 "알려진 gap"으로 이를 명시하며, 비고 컬럼에도 반복해 강조한다.

---

## Java Spring Boot 구현 커버리지

| 원칙 문서 (루트, 공용) | 상태 | Java Spring Boot 구현 문서/코드 | 비고 |
|---|---|---|---|
| [tactical-ddd.md](../architecture/tactical-ddd.md) | **Covered** | `docs/architecture/tactical-ddd.md`, `examples/.../account/domain/Account.java`, `Money.java`, `Transaction.java` | `protected Account()` + `static create()` 정적 팩토리, `record` Value Object(`Money`), 과거형 `record` Domain Event. 설계 자체는 문서·코드 모두 일치. |
| [layer-architecture.md](../architecture/layer-architecture.md) | **Covered (코드는 gap)** | `docs/architecture/layer-architecture.md` | 의존 방향, Command/Query Service, `@Transactional` 전파를 문서화. **`Account`가 `@Entity`/`@Id`/`@Embedded` 등 JPA 애노테이션을 직접 가져 root의 "Domain은 ORM 무의존" 규칙을 위반** — 트레이드오프를 문서에서 정직하게 설명(root 권장 패턴인 척하지 않음). |
| [directory-structure.md](../architecture/directory-structure.md) | **Covered** | `docs/architecture/directory-structure.md` | Account 모듈 + notification(Technical Service) 모듈 실제 트리 전체를 문서화. `interfaces/rest/`(복수형, Java 예약어 `interface` 회피)를 root의 `interface/`(단수)와 명시적으로 대조. |
| [repository-pattern.md](../architecture/repository-pattern.md) | **Covered (코드는 gap)** | `docs/architecture/repository-pattern.md` | 올바른 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 컨벤션과 `take:1` 단건 조회 패턴을 정의. **현재 `AccountRepository`는 `findByAccountIdAndOwnerId`(단건 전용 별도 메서드)/`findAll`/`save`(delete 없음)로 불일치** — gap으로 명시. |
| [aggregate-id.md](../architecture/aggregate-id.md) | **Covered (코드는 gap)** | `docs/architecture/aggregate-id.md` | 32자리 hex, 하이픈 제거 규칙과 `IdGenerator` 유틸을 정의. **현재 `Account.create()`/`Transaction.create()`는 `UUID.randomUUID().toString()`을 하이픈 포함 그대로 사용** — gap으로 명시. |
| [domain-events.md](../architecture/domain-events.md) | **Covered** | `docs/architecture/domain-events.md`, `examples/.../outbox/OutboxEvent.java`, `OutboxWriter.java`, `OutboxRelay.java`, `examples/.../account/application/event/*EventHandler.java` | Outbox 테이블(JPA `@Entity`) + `AccountRepositoryImpl.save()`가 Aggregate와 같은 트랜잭션에서 이벤트를 저장 + Command Service가 저장 직후 `OutboxRelay.processPending()`을 동기 호출하는 경로가 실제로 구현되어 있다. root/nestjs의 `@Scheduled` 폴링 + 메시지 큐 대신 동기 드레인을 쓰는 의도적 차이가 있다(문서에 명시). `ApplicationEventPublisher`/`@EventListener`/`AccountNotificationListener`는 제거됨. |
| [error-handling.md](../architecture/error-handling.md) | **Covered (코드는 gap)** | `docs/architecture/error-handling.md` | `AccountException.ErrorCode` enum, `ErrorResponse{statusCode,code,message,error}` 4필드 구조, `@RestControllerAdvice` 전역 Validation 처리를 정의. **현재 `ErrorResponse`는 `code`/`message` 2필드뿐** — gap으로 명시. 계층별 예외 throw/변환 분리 자체는 이미 올바름. |
| [persistence.md](../architecture/persistence.md) | **Covered (코드는 gap)** | `docs/architecture/persistence.md` | `@Transactional`/`REQUIRES_NEW`(이미 `NotificationServiceImpl`에서 사용 중) 전파, Flyway 마이그레이션, soft delete 배선을 정의. **현재 `ddl-auto: update`(마이그레이션 도구 없음), `deletedAt` 컬럼은 있으나 이를 설정하는 코드가 어디에도 없어 soft delete가 실제로는 작동하지 않음** — gap으로 명시. |
| [testing.md](../architecture/testing.md) | **Covered (코드는 gap)** | `docs/architecture/testing.md` | Domain(순수 Java)/Application(Mockito)/E2E(Testcontainers) 3계층을 정의. **현재는 E2E(`AccountControllerE2ETest`, `NotificationE2ETest`)만 존재** — Domain/Application 단위 테스트는 문서에만 있고 코드로 아직 추가되지 않음. |
| [authentication.md](../architecture/authentication.md) | **Covered (코드는 gap)** | `docs/architecture/authentication.md` | JWT 발급(`SignInService`)/검증(Spring Security `oauth2ResourceServer().jwt()`)/`SecurityConfig` 화이트리스트 패턴을 정의. **현재 `AccountController`는 `X-User-Id` 헤더를 검증 없이 신뢰** — 프로덕션에 반영하면 안 되는 수준의 gap임을 강조. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | **Covered (코드는 gap)** | `docs/architecture/cqrs-pattern.md` | 현재의 경량 CQRS(Command/Query Service 분리)를 문서화하고, `AccountQuery` 인터페이스 도입(올바른 패턴)과 Handler 기반 CQRS 전환 기준을 명시. **`GetAccountService`/`GetTransactionsService`가 쓰기용 `AccountRepository`를 그대로 사용** — root의 "Query는 별도 인터페이스" 규칙 위반을 gap으로 명시. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | **Covered (코드는 gap)** | `docs/architecture/cross-cutting-concerns.md` | `CorrelationIdFilter`(MDC), `RequestLoggingInterceptor` 등 Spring MVC 파이프라인 매핑을 정의. **현재 Correlation ID 주입, 요청 로깅 Interceptor 모두 미구현** — `@Valid` 입력 검증만 이미 구현되어 있음. |
| [config.md](../architecture/config.md) | **Covered (코드는 gap)** | `docs/architecture/config.md` | `@ConfigurationProperties`(record) + `@Validated`로 Fail-fast를 얻는 방법, 관심사별 설정 파일 분리를 정의. **현재 `application.yml`은 단일 파일 16줄, 검증 없음, `aws.access-key-id` 등이 `test` 기본값을 운영/로컬 구분 없이 허용** — gap으로 명시. |
| [container.md](../architecture/container.md) | **Covered (코드는 gap)** | `docs/architecture/container.md` | Layered JAR 기반 Gradle 멀티스테이지 Dockerfile, JRE 베이스, Actuator 헬스체크를 정의. **현재 `examples/`에 Dockerfile 자체가 없음.** |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | **Covered (코드는 gap)** | `docs/architecture/graceful-shutdown.md` | `server.shutdown: graceful`, Actuator liveness/readiness, `AvailabilityChangeEvent` 자동 연동을 정의. **현재 관련 설정 전무**(Actuator 의존성 자체가 없음). |
| [observability.md](../architecture/observability.md) | **Covered (코드는 gap)** | `docs/architecture/observability.md` | 구조화 로깅(Logback JSON 인코더 + `StructuredArguments.kv`), MDC 기반 Correlation ID, Actuator/Micrometer 메트릭을 정의. **현재 SLF4J 직접 사용 + 비구조화 텍스트 로그(`NotificationServiceImpl`, `AccountNotificationListener`), Correlation ID·메트릭 모두 없음.** |
| [scheduling.md](../architecture/scheduling.md) | **Covered** | `docs/architecture/scheduling.md` | `@Scheduled`/`@EnableScheduling`, Task Outbox, FIFO dedup·ShedLock 기반 다중 인스턴스 안전성을 정의. **`@Scheduled` 작업 자체가 아직 없음**(배치 유스케이스 부재) — 문서는 향후 domain-events.md의 Outbox Relay 구현 시 참조용으로 작성됨. |
| [secret-manager.md](../architecture/secret-manager.md) | **Covered (코드는 gap)** | `docs/architecture/secret-manager.md` | AWS Secrets Manager + TTL 캐시(`ConcurrentHashMap`), `EnvironmentPostProcessor` 기반 기동 시 주입을 정의. **현재 Secrets Manager 연동 없음** — `SesConfig`는 `@Value` 환경 변수 기본값(`test`/`test`)만 사용. |
| [api-response.md](../architecture/api-response.md) | **Covered** | `docs/architecture/api-response.md`, `examples/.../GetAccountResult.java`, `GetTransactionsResult.java` | `page`/`take` 쿼리 파라미터, `{ transactions: [...], count }` 목록 응답, 단건 무래퍼 반환 모두 이미 올바름. Repository 반환 형식의 gap은 repository-pattern.md로 교차 참조. |
| [local-dev.md](../architecture/local-dev.md) | **Covered** | `docs/architecture/local-dev.md`, `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | Postgres + LocalStack(SES) healthcheck 구성, 버전 고정, 초기화 스크립트 모두 이미 올바름. `app` 서비스/`profiles: [app]`, `.env.*` 파일 부재는 개선 여지로 문서화(코드 gap이라기보다 확장 여지). |
| [file-storage.md](../architecture/file-storage.md) | **Covered** | `docs/architecture/file-storage.md` | `S3Presigner` 기반 Presigned URL 패턴, `notification/`의 Technical Service 구조를 템플릿으로 재사용하는 방법을 정의. Account 도메인에 첨부파일 유스케이스 자체가 없어 코드 gap이라기보다 미사용 기능. |
| **[domain-service.md](../architecture/domain-service.md)** | **Thin** | `examples/.../notification/application/service/NotificationService.java` + `infrastructure/NotificationServiceImpl.java` | Technical Service 패턴(인터페이스는 application에, 구현체는 infrastructure에) 자체는 코드로 정확히 구현되어 있고, 새로 작성된 `file-storage.md`/`secret-manager.md`/`directory-structure.md`가 이를 반복 참조하며 상세히 설명한다. 다만 `domain-service.md`에 대응하는 **Java 전용 전담 문서는 이번 범위(21개)에 포함되지 않았다** — 필요 시 후속 작업으로 추가 가능. |
| **[cross-domain-communication.md](../architecture/cross-domain-communication.md)** | **Missing** | — | 예제가 단일 BC(Account) 구조라 Adapter(ACL)나 Integration Event 발행/수신 예시가 없다. `notification/`은 별도 BC가 아니라 Technical Service([directory-structure.md](../../implementations/java-springboot/docs/architecture/directory-structure.md)에서 명확히 구분). |
| **[strategic-ddd.md](../architecture/strategic-ddd.md)** | **Missing** | — | Subdomain 분류, BC 식별, Context Map에 대한 Java 관점의 전용 문서 없음 — 이번 범위(21개)에 포함되지 않았다. |

**요약**: 24개 root 아키텍처 문서 중 **Covered 21 (그중 코드 gap이 남아있는 항목 14) / Thin 1 / Missing 2**. 이전 감사(Covered 4 / Thin 9 / Missing 11) 대비 문서 커버리지가 크게 확장되었으나, **문서화 ≠ 코드 반영**이라는 점이 중요하다 — 표의 "코드는 gap" 표시가 붙은 14개 항목은 `examples/`를 아직 고쳐야 문서와 코드가 일치한다. `domain-events.md`는 이번 세션에서 Outbox 패턴이 실제로 구현되어 gap 목록에서 빠졌다.

---

## Java 전용, 대응 root 문서 없음 (기존 guide.md에서 흡수)

| 문서/코드 | 내용 |
|---|---|
| `docs/architecture/tactical-ddd.md` | `record`(Value Object/Command/Result 무비용 불변 표현), `protected` 생성자 + `static create()` 정적 팩토리, package-private 하위 Entity 생성자 — 옛 `guide.md`의 "Aggregate Root"/"CQRS 패턴" 절 내용이 Order 도메인 예시에서 Account 도메인 실제 코드로 갱신되어 이관됨 |
| `docs/architecture/directory-structure.md` | `interfaces/rest/`(복수형, Java 예약어 회피), Lombok `@RequiredArgsConstructor` 관례 — 옛 `guide.md`의 "패키지 구조"/"파일명·패키지 네이밍" 절에서 이관 |
| `docs/architecture/repository-pattern.md` | Spring Data JPA `JpaRepository` + `EntityManager` JPQL 조립을 함께 쓰는 2단 Repository 구조 — 옛 `guide.md`의 "Repository 패턴" 절에서 이관, Order→Account로 예시 갱신 |
| `docs/architecture/persistence.md` | `@SQLRestriction`/soft delete 패턴 — 옛 `guide.md`의 "Soft Delete" 절에서 이관하되, 실제로는 배선되지 않았다는 gap을 새로 명시 |

`guide.md`(258줄, Order 도메인 기준 서술)는 위 내용이 모두 새 문서로 흡수되고 예시가 Account 도메인으로 갱신된 뒤 삭제되었다.

---

## Java Spring Boot 전용, 대응 root 문서 없음 (NestJS 대비 보너스 문서 6종)

`implementations/nestjs/docs/architecture/`에는 root 21개 주제 어디에도 대응하지 않지만 실무 가치가 있는 보너스 문서 6개(`bootstrap.md`/`cross-domain.md`/`design-principles.md`/`module-pattern.md`/`rate-limiting.md`/`shared-modules.md`)가 있다. 이번 세션에서 동일한 6개 주제를 Java Spring Boot 관용으로 다시 작성해 커버리지 공백을 없앴다 — NestJS 문서를 그대로 옮긴 것이 아니라 Spring 고유 메커니즘(생성자 주입, `@ConfigurationProperties`, `@Bean`/`@Configuration`, Resilience4j 등) 기준으로 새로 썼다.

| 문서 | 내용 |
|---|---|
| `docs/architecture/bootstrap.md` | `AccountServiceApplication`의 `@SpringBootApplication`/`SpringApplication.run()` 부트스트랩 순서, `application.yml` 로딩 순서, `@RestControllerAdvice` 전역 예외 처리 배선(error-handling.md 교차 참조), springdoc/CORS/Actuator 도입 가이드(현재 모두 미도입, forward-looking) |
| `docs/architecture/cross-domain.md` | 도메인 간 동기 호출 Adapter 패턴의 Java/Spring 구현 예시 — `application/adapter/` 인터페이스 + `infrastructure/` 구현체. 이 저장소는 단일 BC 구조라 가상의 User BC를 상정한 예시임을 명시 |
| `docs/architecture/design-principles.md` | 이 저장소의 핵심 설계 원칙 13개를 압축한 TL;DR 인덱스 + 알려진 gap(도메인/ORM 결합, Query 인터페이스 미분리, 에러 응답 2필드 등) 요약 표 |
| `docs/architecture/module-pattern.md` | Spring DI 컨테이너 메커니즘(스테레오타입 애노테이션, 생성자 주입, `@Bean`/`@Configuration`, `@Profile`)과 NestJS `@Module`의 근본적 차이(모듈 경계가 컴파일 타임에 강제되지 않음), 순환 의존 시 `@Lazy` vs BC 경계 재설계 |
| `docs/architecture/rate-limiting.md` | Resilience4j `RateLimiter` 기반 `Filter`/애노테이션 구현 예시. `build.gradle` 확인 결과 현재 rate limiting 의존성이 전혀 없어 이 문서는 전적으로 도입 가이드(forward-looking)임을 명시 |
| `docs/architecture/shared-modules.md` | `common/`/`config/`/`database/`/`outbox/`/`auth/` 공유 코드 배치 컨벤션. 현재 `account`/`notification` 두 도메인 패키지뿐이라 공유 패키지가 실재하지 않음을 확인하고, 새로 추가할 때의 권장 배치를 제시 |

---

## harness.sh 검증 범위 — 문서 갱신과 무관하게 여전히 구조 검사에 한정

`implementations/java-springboot/harness/harness.sh`는 여전히 8개 섹션(file-naming, repository-annotation, service-annotation, domain-purity, controller-placement, package-structure, shared-infra, event-placement)으로 구성되며, 파일 배치·어노테이션 위치·디렉토리 존재 여부만 검사한다. 이번 문서화 패스는 `harness/`를 변경하지 않았으므로 다음 한계는 그대로 유지된다:

- `package-structure`는 디렉토리 존재 여부만 확인 — `cqrs-pattern.md`가 명시한 "Query Service가 Repository를 직접 쓰면 안 된다" 위반은 감지하지 못한다.
- `domain-purity`는 Spring 스테레오타입 애노테이션만 금지하고 **JPA 애노테이션은 검사 대상이 아니다** — `layer-architecture.md`가 명시한 도메인/ORM 결합 gap은 이 harness로 잡히지 않는다.
- Repository 메서드 네이밍, 에러 응답 4필드, soft delete 실제 동작, 테스트 레이어 구성 등은 harness에 해당 검사 자체가 없다.

즉 문서가 이제 "Covered"로 표시하는 항목이라도, harness가 그 내용을 실질적으로 강제하는 경우는 거의 없다 — 검증은 전적으로 구조적 배치 규칙에 머물러 있다. harness 자체의 개선은 이번 문서화 패스의 범위 밖이다.

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
- [implementations/nestjs/docs/architecture/](../../implementations/nestjs/docs/architecture/) — 이번 21개 파일 구조의 기준이 된 참고 구현
