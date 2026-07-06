# Kotlin Spring Boot 구현체

## 개요

Kotlin Spring Boot는 JVM 기반 서버로, Spring의 성숙한 생태계(JPA, DI 컨테이너, `@Transactional`)에 Kotlin의 null-safety·`data class`·`sealed class`를 결합한다.
이 플레이북의 원칙을 Kotlin Spring Boot로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/kotlin-springboot/`에 있다.

**→ [implementations/kotlin-springboot/CLAUDE.md](../../implementations/kotlin-springboot/CLAUDE.md)** — Kotlin Spring Boot 구현 상세 가이드 진입점
**→ [implementations/kotlin-springboot/docs/guide.md](../../implementations/kotlin-springboot/docs/guide.md)** — Java와 다른 Kotlin 관용 표현 중심의 단일 가이드 문서
**→ [implementations/kotlin-springboot/examples/](../../implementations/kotlin-springboot/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + SES 알림)
**→ [implementations/kotlin-springboot/harness/harness.sh](../../implementations/kotlin-springboot/harness/harness.sh)** — 가이드 준수 여부를 검증하는 자동 evaluator (9개 검사, 구조·네이밍·어노테이션 위치 중심)

이 문서는 `guide.md` 본문뿐 아니라 `examples/`가 실제로 보여주는 패턴, 그리고 `harness.sh`가 그중 무엇을 검증하는지까지 함께 확인해 작성했다. `guide.md`는 239줄 분량으로 Java 버전 대비 짧고, "Java와 구조는 동일하며 Kotlin 관용 표현 차이만 다룬다"는 전제 하에 패키지 구조·데이터클래스·null 안전성·`open class`·CQRS·Aggregate·에러 처리·Soft Delete만 다룬다. 아래 표는 그 범위를 벗어나는 root 문서 다수에서 Kotlin 쪽 커버리지가 얕거나 없다는 것을 보여준다.

---

## Kotlin Spring Boot 구현 커버리지

| 원칙 문서 (루트, 공용) | 상태 | Kotlin Spring Boot 구현 문서/코드 | 비고 (Kotlin/Spring 관용 메커니즘, harness 검증 여부) |
|---|---|---|---|
| [tactical-ddd.md](../architecture/tactical-ddd.md) | **Covered** | `guide.md` "Aggregate Root" 절, `examples/.../account/domain/Account.kt`, `Money.kt`, `Transaction.kt` | Aggregate(`Account`)는 `protected constructor()` + `companion object.create()`로 생성 통제, 불변식은 `check()`/`require()`로 즉시 예외. VO(`Money`)는 `data class`로 속성 기반 동등성을 컴파일러가 자동 생성 — TS/Java의 수동 `equals()`보다 안전. Domain Event는 `domainEvents: MutableList<Any>` + `pullDomainEvents()`. harness 검증: `sealed-exception` 검사가 예외 계층의 `domain/` 배치만 확인, Aggregate 불변식·VO 동등성 자체는 미검증. |
| [layer-architecture.md](../architecture/layer-architecture.md) | **Covered** | `guide.md` "Repository 패턴", "CQRS 패턴" 절, `examples/.../application/command/*Service.kt`, `application/query/*Service.kt` | Command/Query Service 분리는 root와 동일. 트랜잭션 전파는 root의 수동 AsyncLocalStorage 패턴 대신 Spring 선언적 `@Transactional`(스레드바운드 커넥션, `@Transactional(readOnly = true)`로 읽기 최적화)로 대체 — Kotlin/Spring 고유의 관용적 우위. harness 검증: `domain-purity`(도메인에 Spring 어노테이션 금지), `service-annotation`(`@Service`는 application/ 안에만) 검사가 레이어 의존 방향의 일부를 실제로 강제함. |
| [directory-structure.md](../architecture/directory-structure.md) | **Covered** | `guide.md` "패키지 구조" 절, `CLAUDE.md` "구현 원칙 요약" | root의 kebab-case 파일명 대신 Kotlin/Java 관용의 PascalCase 파일명(`OrderRepository.kt`) 사용. `interface/`가 아닌 `interfaces/rest/`(복수형 + 하위 패키지), `infrastructure/persistence/` 서브패키지 등 Java Spring 생태계 관례를 그대로 채택 — 문서화되어 있고 실제 코드와 일치함. harness 검증: `file-naming`(PascalCase 강제), `package-structure`(4레이어 + command/query 서브디렉터리 존재)로 실제로 강제됨. |
| [local-dev.md](../architecture/local-dev.md) | **Covered** | `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | Postgres + LocalStack(SES) 컨테이너에 `healthcheck` 적용, `AWS_ENDPOINT_URL` 분기로 로컬/운영 전환 — root 패턴과 일치. 단, `profiles: [app]`로 앱 컨테이너를 선택 실행하는 패턴은 compose에 앱 서비스 자체가 없어 보여주지 못함(로컬에서는 `./gradlew bootRun` 사용 전제). harness 검증 없음(구조 검사 대상 아님). |
| [api-response.md](../architecture/api-response.md) | **Covered** | `examples/.../account/application/query/GetAccountResult.kt`, `GetTransactionsResult.kt`, `AccountController.kt` | 목록 응답은 `{ transactions: [...], count: ... }` 형태로 도메인 객체 복수형 키 원칙을 지킴. `page`/`take` 기본값(0/20)도 root와 동일. Query는 Aggregate가 아닌 별도 `data class` Result(`GetAccountResult`)를 반환해 "Result 객체 설계" 원칙을 그대로 따름. 다만 단건 조회가 root가 금지하는 전용 `findByAccountIdAndOwnerId` 메서드로 구현되어 "findOne 없이 take:1 재사용" 원칙과는 다르다(repository-pattern.md 항목 참고). harness 검증 없음. |
| **[aggregate-id.md](../architecture/aggregate-id.md)** | **Thin** | `guide.md` "Aggregate Root" 절 예시, `Account.create()`, `Order.create()` | ID 생성 위치(도메인 계층 `companion object.create()`)는 root 원칙과 일치. 그러나 실제 생성 코드가 `UUID.randomUUID().toString()`을 하이픈 제거 없이 그대로 사용한다 — root가 명시적으로 금지하는 "하이픈 포함 UUID" 형식과 다르다. guide.md에도 하이픈 제거에 대한 언급이 없다. harness 검증 없음(ID 포맷은 검사 대상 아님). |
| **[cqrs-pattern.md](../architecture/cqrs-pattern.md)** | **Thin** | `guide.md` "CQRS 패턴" 절, `examples/.../application/command/*`, `application/query/*` | root 문서가 말하는 두 단계 중 "기본 아키텍처"(Command/Query Service 분리)만 구현되어 있다. 이 문서가 실제로 다루는 **Handler 기반 CQRS**(CommandBus/QueryBus, 독립 Handler 클래스)는 guide.md에도 examples에도 전혀 없다 — Spring이 `@nestjs/cqrs` 같은 표준 CQRS 프레임워크를 기본 제공하지 않는 사정과도 맞물려 있지만, Axon Framework 등 대안 언급조차 없다. harness 검증 없음. |
| **[domain-events.md](../architecture/domain-events.md)** | **Thin** | `guide.md`는 언급 없음. `examples/.../account/application/event/AccountNotificationListener.kt`, `Account.pullDomainEvents()` | 이벤트 수집(`domainEvents` 리스트 + `pullDomainEvents()`)은 Aggregate 캡슐화 원칙과 일치. 그러나 실제 발행 메커니즘은 Spring `ApplicationEventPublisher` 기반의 **동기 in-process `@EventListener`**다 — root 문서가 "Domain Event는 in-process 이벤트 버스를 사용하지 않는다. Outbox → 메시지 큐 → EventConsumer 경로로 전달된다"고 명시적으로 금지하는 패턴과 정면으로 다르다. Outbox 테이블, at-least-once 멱등성 3단계 전략, Integration Event 개념 모두 부재. 코드 주석 자체가 "알림 발송 실패가 커맨드를 실패시키지 않도록 try/catch"라고만 완화하고 있어, 설계상 의도된 단순화이지 실수는 아니지만 root 패턴과의 괴리는 명확하다. harness의 `event-placement` 검사는 `@EventListener` 클래스가 `application/event/` 안에 있는지만 확인하고 Outbox 여부는 확인하지 않는다(`shared-infra` 검사는 `*Outbox*.kt` 파일이 없으면 스킵되어 이 저장소 예제에서는 자동 통과). |
| **[domain-service.md](../architecture/domain-service.md)** | **Thin** | `guide.md`는 Domain Service/Technical Service를 별도로 다루지 않음. `examples/.../notification/application/service/NotificationService.kt` + `infrastructure/NotificationServiceImpl.kt` | Technical Service 패턴(Application에 인터페이스, Infrastructure에 구현체)은 `NotificationService`/`NotificationServiceImpl`로 실질적으로, 코드 주석에 root 문서까지 인용하며 잘 구현되어 있다. 그러나 이는 examples에서만 확인되고 guide.md 텍스트에는 이 패턴 자체에 대한 설명이 없다. 여러 Aggregate를 조율하는 순수 Domain Service 예시는 코드에도 없다. harness 검증 없음. |
| **[error-handling.md](../architecture/error-handling.md)** | **Thin** | `guide.md` "에러 처리" 절, `examples/.../domain/AccountException.kt`, `AccountController.kt`의 `@ExceptionHandler` | `sealed class AccountException`으로 도메인 예외 계층을 만든 것은 root의 "plain Error + 타입화된 에러 메시지" 원칙을 Kotlin다운 방식(컴파일러가 `when` 분기 완전성을 검사할 수 있는 sealed 계층)으로 잘 표현한다. 그러나 응답 포맷은 `ErrorResponse(message: String)` 하나뿐이라 root가 요구하는 `statusCode`/`code`/`message`/`error` 4필드 구조, `<Domain>ErrorCode` enum과의 1:1 매핑이 없다. 클라이언트가 `code`로 분기할 방법이 없다. harness 검증 없음. |
| **[persistence.md](../architecture/persistence.md)** | **Thin** | `guide.md` "Soft Delete" 절(개념 예시), `Account.kt`의 `createdAt`/`updatedAt`/`deletedAt` 컬럼 | 공통 컬럼은 있으나 재사용 가능한 `BaseEntity` 상속 없이 Aggregate마다 반복 선언된다. Soft Delete는 guide.md에는 개념적으로 나오지만, 실제 `examples/`의 `AccountRepository` 인터페이스에는 **delete 메서드 자체가 없다** — soft delete가 실제로 배선되어 실행되는 경로가 없다. 트랜잭션 전파는 `@Transactional`로 대체(합리적). 마이그레이션 파일이 전혀 없고 `application.yml`이 `ddl-auto: update`를 사용한다 — root가 "운영 환경에서는 반드시 마이그레이션을 사용해야 한다"고 명시적으로 경고하는 패턴을 예제가 그대로 쓰고 있다(예제 성격상 허용될 수 있으나 guide.md에 프로덕션 마이그레이션 도구 언급이 전혀 없다). harness 검증 없음. |
| **[repository-pattern.md](../architecture/repository-pattern.md)** | **Thin** | `guide.md` "Repository 패턴" 절, `examples/.../domain/AccountRepository.kt`, `infrastructure/persistence/AccountRepositoryImpl.kt` | 1 Aggregate = 1 Repository 인터페이스/구현체 분리, `domain/`에 인터페이스·`infrastructure/`에 구현체 배치는 정확히 지켜졌다. 그러나 메서드 네이밍은 root의 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 대신 Spring Data 관용(`findAll`, `save`)과 전용 단건 조회 메서드(`findByAccountIdAndOwnerId`)를 섞어 쓴다 — root가 명시적으로 금지하는 "findOne 전용 메서드" 패턴이다. `delete<Noun>` 메서드는 인터페이스에 아예 없다. harness는 `@Repository` 어노테이션이 `infrastructure/` 안에 있는지만 확인하고 메서드 네이밍은 검증하지 않는다. |
| **[conventions.md](../conventions.md)** | **Thin** | `examples/.../interfaces/rest/AccountController.kt` | REST URL 설계(`/accounts`, `/accounts/:id/deposit` 등 비 CRUD 행위의 하위 리소스 경로화)와 HTTP 상태 코드(201/204/200 구분)는 root 컨벤션과 정확히 일치한다. 그러나 Rate Limiting은 guide.md·examples 어디에도 없고, Repository 메서드 네이밍 컨벤션도 위 항목처럼 어긋난다. 커밋/브랜치 컨벤션은 코드로 검증할 대상이 아니라 이 저장소의 워크플로우로 별도 관리됨. harness 검증 없음. |
| **[testing.md](../architecture/testing.md)** | **Thin** | `examples/.../src/test/kotlin/.../AccountControllerE2ETest.kt` | E2E 레이어만 존재한다 — Testcontainers(Postgres + LocalStack SES)로 실제 HTTP 요청·이메일 발송까지 검증하는 점은 훌륭하다. 그러나 root가 요구하는 3계층 중 **Domain 단위 테스트**(`Account.kt`를 프레임워크 없이 직접 `new`해서 불변식 검증)와 **Application 단위 테스트**(Repository를 mock으로 대체)가 하나도 없다 — 테스트 피라미드가 최상위 계층에만 존재하는 역피라미드 상태. guide.md에는 테스트 전략 언급 자체가 없다. harness 검증 없음(테스트 존재 여부/구성은 검사 대상 아님). |
| **[authentication.md](../architecture/authentication.md)** | **Missing** | — | guide.md에 인증 언급이 전혀 없다. examples는 `@RequestHeader("X-User-Id")`로 클라이언트가 보낸 값을 그대로 신뢰한다 — JWT 발급/검증, Bearer 토큰, Guard/Interceptor 계층이 전무하다. 프로덕션에 그대로 반영하면 안 되는 수준의 공백. |
| **[config.md](../architecture/config.md)** | **Missing** | — | 관심사별 설정 파일 분리, 기동 시 Fail-fast 환경 변수 검증(`config-validator`)이 guide.md·examples 어디에도 없다. `application.yml`은 3줄뿐이고 필수값 누락 시 검증 로직이 없다. |
| **[container.md](../architecture/container.md)** | **Missing** | — | `implementations/kotlin-springboot/examples/`에 Dockerfile이 아예 없다(파일 목록 확인 완료). 멀티스테이지 빌드, `.dockerignore`, exec-form CMD, 헬스체크 엔드포인트 원칙에 대한 언급이 guide.md에도 없다. |
| **[cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md)** | **Missing** | — | Correlation ID 주입, Middleware/Guard/Pipe/Interceptor 단계 구분이 없다. `AccountController`가 직접 헤더를 읽어 쓰는 방식이라 "Handler는 순수하게" 원칙과도 거리가 있다. |
| **[cross-domain-communication.md](../architecture/cross-domain-communication.md)** | **Missing** | — | 예제가 사실상 단일 BC(Account) 구조라 Adapter(ACL)나 Integration Event 발행/수신 예시가 없다. `notification/` 패키지는 별도 BC라기보다 Account BC의 Technical Service에 가깝다. guide.md에도 BC 간 통신 패턴 설명이 없다. |
| **[graceful-shutdown.md](../architecture/graceful-shutdown.md)** | **Missing** | — | Liveness/Readiness 엔드포인트, `terminationGracePeriodSeconds` 대응, SIGTERM 처리 순서에 대한 내용이 guide.md·examples 어디에도 없다. |
| **[observability.md](../architecture/observability.md)** | **Missing** | — | 로그 레벨 정책, 구조화 로그(snake_case 필드), Correlation ID 전파가 없다. `NotificationServiceImpl`의 SLF4J 로깅은 파라미터화된 일반 로그일 뿐 구조화 로그가 아니다. |
| **[scheduling.md](../architecture/scheduling.md)** | **Missing** | — | `@Scheduled`/Cron, TaskQueue, Task Outbox, DLQ 관련 내용이 전혀 없다. |
| **[secret-manager.md](../architecture/secret-manager.md)** | **Missing** | — | SES 자격 증명은 `@Value`로 환경 변수를 직접 읽으며 Secrets Manager 연동이나 TTL 캐시가 없다. |
| **[strategic-ddd.md](../architecture/strategic-ddd.md)** | **Missing** | — | Subdomain 분류, BC 식별, Context Map, 유비쿼터스 언어 사전에 대한 Kotlin 관점의 설명이 guide.md에 없다. |
| **[file-storage.md](../architecture/file-storage.md)** | **Missing** | — | Presigned URL, StorageService 추상화, S3/GCS 연동 예시가 전혀 없다. |

**요약**: 24개 root 아키텍처 문서 + `conventions.md` 총 25개 중 **Covered 5 / Thin 9 / Missing 11 / N/A 0**.

---

## Kotlin 전용, 대응 root 문서 없음

| 문서/코드 | 내용 |
|---|---|
| `guide.md` "Java와 다른 Kotlin 관용 표현" 절 | `data class`(Lombok 불필요), Nullable 타입(`Order?`, `Optional` 불필요), Constructor injection(`@Autowired` 불필요), `sealed class` 에러 타입 계층, `open class`/`kotlin-spring` 플러그인(Spring AOP 프록시를 위해 Kotlin 기본 `final` 클래스를 열어야 하는 문제와 해법) |
| `Account.kt`의 `check()`/`require()` 사용 | Kotlin 표준 라이브러리의 사전조건 함수로 불변식 위반 시 `IllegalStateException`/`IllegalArgumentException`을 던진다 — TS의 수동 `if (...) throw new Error(...)`보다 간결한 Kotlin 고유 관용구 |
| `Money.kt`의 `data class` + `init {}` 블록 | Value Object 동등성(`equals`/`hashCode`/`copy`)을 컴파일러가 자동 생성하고, 생성자 검증은 `init` 블록에서 수행 — root의 수동 `equals()` 구현이 필요 없다 |

---

## Kotlin Spring Boot 선택 이유

- **Null-safety가 타입 시스템에 내장**: `Order?`처럼 nullable 여부가 컴파일 타임에 강제되어, Java의 `Optional<T>` 래핑이나 런타임 `NullPointerException` 방어 코드 없이도 "찾지 못함"을 표현할 수 있다.
- **`data class`로 불변 DTO/VO를 무비용으로 표현**: Command, Result, Value Object 모두 Lombok이나 수동 `equals()`/`hashCode()`/`toString()` 없이 한 줄로 선언되며, `copy()`로 불변 갱신도 자연스럽다.
- **`sealed class`로 에러 타입 계층을 컴파일러가 검증**: 도메인 예외를 `sealed class`로 묶으면 `when` 분기에서 컴파일러가 완전성(exhaustiveness)을 검사해, 새 예외 타입 추가 시 처리 누락을 컴파일 타임에 잡아낸다.
- **Java Spring 생태계와 100% 상호운용**: JPA, Spring MVC, Testcontainers 등 Java 생태계 라이브러리를 그대로 사용하면서도 언어 차원의 안전성만 Kotlin으로 얻는다 — 단, 이는 `open class`/`kotlin-spring` 플러그인처럼 Kotlin 기본값(클래스가 `final`)과 Spring AOP 프록시 요구사항이 충돌하는 지점을 별도로 관리해야 하는 비용을 동반한다.

---

### 관련 문서

- [implementations/java-springboot/](../../implementations/java-springboot/) — 동일 아키텍처의 Java 버전 (구조는 동일, 언어 관용구만 다름)
