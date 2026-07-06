# Kotlin Spring Boot 구현체

## 개요

Kotlin Spring Boot는 JVM 기반 서버로, Spring의 성숙한 생태계(JPA, DI 컨테이너, `@Transactional`)에 Kotlin의 null-safety·`data class`·`sealed class`를 결합한다.
이 플레이북의 원칙을 Kotlin Spring Boot로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/kotlin-springboot/`에 있다.

**→ [implementations/kotlin-springboot/CLAUDE.md](../../implementations/kotlin-springboot/CLAUDE.md)** — Kotlin Spring Boot 구현 상세 가이드 진입점 (키워드 → 문서 인덱스)
**→ [implementations/kotlin-springboot/docs/architecture/](../../implementations/kotlin-springboot/docs/architecture/)** — root의 21개 아키텍처 주제 각각에 대한 Kotlin 전용 상세 문서 (21개 파일)
**→ [implementations/kotlin-springboot/examples/](../../implementations/kotlin-springboot/examples/)** — Account 도메인 전체 구현 예시 (계좌 개설/입출금/정지/재개/종료 + SES 알림)
**→ [implementations/kotlin-springboot/harness/harness.sh](../../implementations/kotlin-springboot/harness/harness.sh)** — 가이드 준수 여부를 검증하는 자동 evaluator (9개 검사, 구조·네이밍·어노테이션 위치 중심)

이 감사는 이전 세션(단일 `guide.md` 239줄 기반)에서 크게 개편되었다. `guide.md`는 root 21개 주제 중 패키지 구조·데이터클래스·null 안전성·CQRS·Aggregate·에러 처리·Soft Delete 등 일부만 다루며 "Java와 구조는 동일하다"는 전제로 나머지를 생략했었다. 이제 root `docs/architecture/`의 21개 파일 각각에 대응하는 Kotlin 전용 문서가 `docs/architecture/`에 있고, `guide.md`의 실질적 내용은 모두 흡수된 뒤 파일 자체는 삭제되었다. 아래 표는 그 결과를 반영한다 — **문서화 수준은 대부분 Covered로 올라갔지만, `examples/`의 실제 코드는 아직 여러 항목에서 문서가 규정하는 올바른 패턴을 따르지 않는다.** 각 문서가 "알려진 갭"으로 이를 명시하며, 비고 컬럼에도 반복해 강조한다.

---

## Kotlin Spring Boot 구현 커버리지

| 원칙 문서 (루트, 공용) | 상태 | Kotlin Spring Boot 구현 문서/코드 | 비고 |
|---|---|---|---|
| [tactical-ddd.md](../architecture/tactical-ddd.md) | **Covered** | `docs/architecture/tactical-ddd.md`, `examples/.../account/domain/Account.kt`, `Money.kt`, `Transaction.kt` | `protected constructor()` + `companion object.create()`, `data class` Value Object, `sealed class` 예외. 문서·코드 모두 일치. |
| [layer-architecture.md](../architecture/layer-architecture.md) | **Covered** | `docs/architecture/layer-architecture.md` | 의존 방향, null-safety를 통한 "찾지 못함" 표현, `@Transactional`을 통한 트랜잭션 전파를 문서화. harness의 `domain-purity`/`service-annotation`/`repository-annotation`이 레이어 분리를 실제로 강제. |
| [directory-structure.md](../architecture/directory-structure.md) | **Covered** | `docs/architecture/directory-structure.md` | Account 모듈 + notification(Technical Service) 모듈 실제 트리 전체를 문서화. PascalCase 파일명, `interfaces/rest/`(복수형) 등 Java/Kotlin 관례를 root의 kebab-case와 명시적으로 대조. |
| [repository-pattern.md](../architecture/repository-pattern.md) | **Covered (코드는 갭)** | `docs/architecture/repository-pattern.md` | 올바른 `find<Noun>s`/`save<Noun>`/`delete<Noun>` 컨벤션과 `take:1` 단건 조회 패턴을 정의. **현재 `AccountRepository`는 `findByAccountIdAndOwnerId`/`findAll`/`save`(delete 없음)로 여전히 불일치** — 문서에 갭으로 명시, 코드 수정은 후속 작업. |
| [aggregate-id.md](../architecture/aggregate-id.md) | **Covered (코드는 갭)** | `docs/architecture/aggregate-id.md` | 32자리 hex, 하이픈 제거 규칙과 `generateId()` 유틸을 정의. **현재 `Account.create()`/`Transaction.create()`는 `UUID.randomUUID().toString()`을 하이픈 포함 그대로 사용** — 갭으로 명시. |
| [domain-events.md](../architecture/domain-events.md) | **Covered (코드는 갭)** | `docs/architecture/domain-events.md` | Outbox 테이블 스키마, Relay, Consumer, 멱등성 3단계, `sealed interface` 이벤트 계층 제안까지 상세 기술. **현재는 `ApplicationEventPublisher`/`@EventListener` 기반 동기 in-process 발행** — root가 명시적으로 금지하는 패턴임을 문서에서 강조. |
| [error-handling.md](../architecture/error-handling.md) | **Covered (코드는 갭)** | `docs/architecture/error-handling.md` | `AccountErrorCode` enum, `ErrorResponse{statusCode,code,message,error}` 4필드 구조, `@RestControllerAdvice` 전역 처리를 정의. **현재 `ErrorResponse`는 `message` 필드 하나뿐** — 갭으로 명시. `sealed class AccountException` 계층 자체는 이미 올바름. |
| [persistence.md](../architecture/persistence.md) | **Covered (코드는 갭)** | `docs/architecture/persistence.md` | `@Transactional` 전파, `@MappedSuperclass` BaseEntity, Flyway 마이그레이션을 정의. **현재 `ddl-auto: update`(마이그레이션 없음), Soft Delete는 `deletedAt` 컬럼만 있고 delete 메서드 자체가 없어 실행 경로 부재** — 갭으로 명시. |
| [testing.md](../architecture/testing.md) | **Covered (코드는 갭)** | `docs/architecture/testing.md` | Domain(순수 Kotlin)/Application(MockK)/E2E(Testcontainers) 3계층을 정의, MockK 채택 근거(Kotlin final 클래스 친화성)를 명시. **현재는 E2E(`AccountControllerE2ETest.kt`)만 존재** — Domain/Application 단위 테스트는 문서에만 있고 코드로 아직 추가되지 않음. |
| [authentication.md](../architecture/authentication.md) | **Covered (코드는 갭)** | `docs/architecture/authentication.md` | JWT 발급(`AuthService`)/검증(`JwtAuthenticationFilter`)/`SecurityConfig` 화이트리스트 패턴을 상세 정의. **현재 `AccountController`는 `X-User-Id` 헤더를 검증 없이 신뢰** — 프로덕션에 반영하면 안 되는 수준의 갭임을 강조. |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | **Covered** | `docs/architecture/cqrs-pattern.md` | 현재의 Command/Query Service 분리(기본 아키텍처)를 문서화하고, Handler 기반 CQRS 전환 기준(Axon Framework 등 대안 포함)을 명시. Handler 기반 CQRS 자체는 미도입 — 규모상 불필요하다고 판단한 근거를 문서에 기록. |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | **Covered (코드는 갭)** | `docs/architecture/cross-cutting-concerns.md` | `CorrelationIdFilter`(MDC), `RequestLoggingInterceptor` 등 Spring MVC 파이프라인 매핑을 정의. **현재 Correlation ID 주입, 요청 로깅 Interceptor 모두 미구현.** |
| [config.md](../architecture/config.md) | **Covered (코드는 갭)** | `docs/architecture/config.md` | `@ConfigurationProperties` + `data class` + `@Validated`로 Fail-fast를 얻는 방법을 정의. **현재 `application.yml`은 3줄, 관심사별 분리·검증 없음.** |
| [container.md](../architecture/container.md) | **Covered (코드는 갭)** | `docs/architecture/container.md` | Gradle 멀티스테이지 Dockerfile, JRE 베이스, Actuator 헬스체크를 정의. **현재 `examples/`에 Dockerfile 자체가 없음.** |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | **Covered (코드는 갭)** | `docs/architecture/graceful-shutdown.md` | `server.shutdown: graceful`, Actuator liveness/readiness, `ThreadPoolTaskScheduler` 종료 대기를 정의. **현재 관련 설정 전무.** |
| [observability.md](../architecture/observability.md) | **Covered (코드는 갭)** | `docs/architecture/observability.md` | 구조화 로깅(JSON/snake_case), MDC 기반 Correlation ID, `kotlin-logging` 전환 근거를 정의. **현재 SLF4J 직접 사용 + 비구조화 텍스트 로그, Correlation ID 없음.** |
| [scheduling.md](../architecture/scheduling.md) | **Covered** | `docs/architecture/scheduling.md` | `@Scheduled`, Task Outbox, 코루틴을 사용하지 않는 이유(블로킹 MVC+JPA 스택)를 정의. **`@Scheduled` 작업 자체가 아직 없음**(배치 유스케이스 부재) — 문서는 향후 Outbox Relay 구현 시 참조용. |
| [secret-manager.md](../architecture/secret-manager.md) | **Covered (코드는 갭)** | `docs/architecture/secret-manager.md` | AWS Secrets Manager + TTL 캐시(`ConcurrentHashMap`), `@Profile` 기반 로컬/운영 분기를 정의. **현재 Secrets Manager 연동 없음** — SES 발신자 이메일처럼 민감하지 않은 값만 환경 변수로 주입. |
| [api-response.md](../architecture/api-response.md) | **Covered** | `docs/architecture/api-response.md`, `examples/.../GetAccountResult.kt`, `GetTransactionsResult.kt` | 페이지네이션, nested `data class` Result 설계는 이미 올바름. Repository 반환 형식의 갭은 repository-pattern.md로 교차 참조. |
| [local-dev.md](../architecture/local-dev.md) | **Covered** | `docs/architecture/local-dev.md`, `examples/docker-compose.yml`, `examples/localstack/init-ses.sh` | Postgres + LocalStack(SES) healthcheck 구성은 이미 올바름. `app` 서비스/`profiles: [app]` 부재는 Gradle 개발 흐름상 의도된 차이로 문서화. |
| [file-storage.md](../architecture/file-storage.md) | **Covered** | `docs/architecture/file-storage.md` | `S3Presigner` 기반 Presigned URL 패턴, `notification/`의 Technical Service 구조를 템플릿으로 재사용하는 방법을 정의. Account 도메인에 첨부파일 유스케이스 자체가 없어 코드 갭이라기보다 미사용 기능. |
| **[domain-service.md](../architecture/domain-service.md)** | **Thin** | `examples/.../notification/application/service/NotificationService.kt` + `infrastructure/NotificationServiceImpl.kt` | Technical Service 패턴 자체는 코드로 실질적으로 잘 구현되어 있고, 새로 작성된 `file-storage.md`/`secret-manager.md`/`directory-structure.md`가 이를 반복 참조하며 상세히 설명한다. 다만 `domain-service.md`에 대응하는 **Kotlin 전용 전담 문서는 이번 범위(21개)에 포함되지 않았다** — 필요 시 후속 작업으로 추가 가능. |
| **[cross-domain-communication.md](../architecture/cross-domain-communication.md)** | **Missing** | — | 예제가 단일 BC(Account) 구조라 Adapter(ACL)나 Integration Event 발행/수신 예시가 없다. `notification/`은 별도 BC가 아니라 Technical Service([directory-structure.md](../../implementations/kotlin-springboot/docs/architecture/directory-structure.md)에서 명확히 구분). |
| **[strategic-ddd.md](../architecture/strategic-ddd.md)** | **Missing** | — | Subdomain 분류, BC 식별, Context Map에 대한 Kotlin 관점의 전용 문서 없음 — 이번 범위(21개)에 포함되지 않았다. |
| **[conventions.md](../conventions.md)** | **Thin** | `examples/.../interfaces/rest/AccountController.kt` | REST URL 설계, HTTP 상태 코드 구분은 root와 일치. Rate Limiting은 여전히 문서·코드 어디에도 없다. Repository 메서드 네이밍 불일치는 이제 [repository-pattern.md](../../implementations/kotlin-springboot/docs/architecture/repository-pattern.md)에 상세히 문서화됨. |

**요약**: 24개 root 아키텍처 문서 + `conventions.md` 총 25개 중 **Covered 21 (그중 코드 갭이 남아있는 항목 14) / Thin 2 / Missing 2**. 이전 감사(Covered 5 / Thin 9 / Missing 11) 대비 문서 커버리지가 크게 확장되었으나, **문서화 ≠ 코드 반영**이라는 점이 중요하다 — 표의 "코드는 갭" 표시가 붙은 14개 항목은 `examples/`를 아직 고쳐야 문서와 코드가 일치한다.

---

## Kotlin 전용, 대응 root 문서 없음 (기존 guide.md에서 흡수)

| 문서/코드 | 내용 |
|---|---|
| `docs/architecture/tactical-ddd.md`, `layer-architecture.md` | `data class`(Lombok 불필요), Nullable 타입(`Order?`, `Optional` 불필요), Constructor injection(`@Autowired` 불필요), `sealed class` 에러 타입 계층 — 옛 `guide.md`의 "Java와 다른 Kotlin 관용 표현" 절 내용이 여기로 이관됨 |
| `docs/architecture/layer-architecture.md`, `directory-structure.md` | `open class`/`kotlin-spring` 플러그인(Spring AOP 프록시를 위해 Kotlin 기본 `final` 클래스를 열어야 하는 문제와 해법) — 옛 `guide.md`에서 이관 |
| `Account.kt`의 `check()`/`require()` 사용 | Kotlin 표준 라이브러리의 사전조건 함수로 불변식 위반 시 예외를 던진다 — `tactical-ddd.md`에서 설명 |
| `Money.kt`의 `data class` + `init {}` 블록 | Value Object 동등성을 컴파일러가 자동 생성 — `tactical-ddd.md`에서 설명 |

`guide.md`(239줄)는 위 내용이 모두 새 문서로 흡수된 뒤 삭제되었다.

---

## Kotlin Spring Boot 전용, 대응 root 문서 없음

NestJS 구현체가 이미 6개의 "NestJS 전용" 문서(`implementations/nestjs/docs/architecture/{bootstrap,cross-domain,design-principles,module-pattern,rate-limiting,shared-modules}.md`, [docs/implementations/nestjs.md](nestjs.md) 참조)를 갖고 있던 것과 대칭을 맞추기 위해, 동일한 6개 주제를 Kotlin/Spring 관용구로 새로 작성했다 — NestJS 문서의 직역이 아니라 `data class`/`sealed class`/null-safety/생성자 주입 등 Kotlin/Spring의 실제 관례에 맞춰 다시 쓴 것이다.

| 원칙 문서 (루트, 공용) | Kotlin Spring Boot 구현 문서 |
|---|---|
| — (Kotlin Spring Boot 전용, 대응하는 root 문서 없음) | `implementations/kotlin-springboot/docs/architecture/bootstrap.md` — `AccountServiceApplication.kt`, `runApplication<T>()`, `application.yml` 로딩 순서, Swagger/CORS 도입 가이드 |
| — (Kotlin Spring Boot 전용) | `implementations/kotlin-springboot/docs/architecture/cross-domain.md` — Adapter 패턴 구현 상세(가상 예시, Account BC → User BC), 원칙은 [cross-domain-communication.md](../architecture/cross-domain-communication.md) 참고 |
| — (Kotlin Spring Boot 전용) | `implementations/kotlin-springboot/docs/architecture/design-principles.md` — 핵심 설계 원칙 15개 요약 |
| — (Kotlin Spring Boot 전용) | `implementations/kotlin-springboot/docs/architecture/module-pattern.md` — Spring DI 컨테이너, `@Component`/`@Service`/`@Bean`, 순환 의존 회피 |
| — (Kotlin Spring Boot 전용) | `implementations/kotlin-springboot/docs/architecture/rate-limiting.md` — Resilience4j `RateLimiter` 기반 Filter (현재 `examples/`는 미구현) |
| — (Kotlin Spring Boot 전용) | `implementations/kotlin-springboot/docs/architecture/shared-modules.md` — `common/`/`config/`/`auth/`/`outbox/` 공유 패키지 컨벤션 (현재 `examples/`는 `account/`/`notification/`뿐) |

---

## Kotlin Spring Boot 선택 이유

- **Null-safety가 타입 시스템에 내장**: `Account?`처럼 nullable 여부가 컴파일 타임에 강제되어, Java의 `Optional<T>` 래핑이나 런타임 `NullPointerException` 방어 코드 없이도 "찾지 못함"을 표현할 수 있다.
- **`data class`로 불변 DTO/VO를 무비용으로 표현**: Command, Result, Value Object 모두 Lombok이나 수동 `equals()`/`hashCode()`/`toString()` 없이 한 줄로 선언되며, `copy()`로 불변 갱신도 자연스럽다.
- **`sealed class`/`sealed interface`로 타입 계층을 컴파일러가 검증**: 도메인 예외·이벤트를 봉인하면 `when` 분기에서 컴파일러가 완전성(exhaustiveness)을 검사해, 새 타입 추가 시 처리 누락을 컴파일 타임에 잡아낸다.
- **Java Spring 생태계와 100% 상호운용**: JPA, Spring MVC, Testcontainers 등 Java 생태계 라이브러리를 그대로 사용하면서도 언어 차원의 안전성만 Kotlin으로 얻는다 — 단, `open class`/`kotlin-spring` 플러그인처럼 Kotlin 기본값(클래스가 `final`)과 Spring AOP 프록시 요구사항이 충돌하는 지점은 별도로 관리해야 한다.

---

### 관련 문서

- [implementations/java-springboot/](../../implementations/java-springboot/) — 동일 아키텍처의 Java 버전 (Spring 메커니즘은 유사하지만 언어 관용구는 다름)
- [implementations/nestjs/docs/architecture/](../../implementations/nestjs/docs/architecture/) — 이번 21개 파일 구조의 기준이 된 참고 구현
