# Kotlin Spring Boot 구현 가이드

DDD 기반 Kotlin Spring Boot 서버 프로젝트의 설계/구현 가이드이다.
`<domain>/{domain,application,infrastructure,interfaces}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 Kotlin Spring Boot 구현 상세에 집중한다. Java Spring Boot와 Spring 프레임워크 메커니즘 자체는 상당 부분 겹치지만, 각 문서는 Kotlin 관용(null-safety, `data class`, `sealed class`, `companion object` 등)으로 다시 쓰여 있다 — Java 문서를 그대로 옮긴 것이 아니다.

## 작업 시 참조할 문서

### 레이어 · 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 프로젝트 구조, 패키지 레이아웃, Account/notification 모듈 실제 구조 | `docs/architecture/directory-structure.md` |
| 앱 부트스트랩, `AccountServiceApplication.kt`, `runApplication`, `application.yml` 로딩 순서, Swagger/CORS 도입 | `docs/architecture/bootstrap.md` |
| 레이어 역할, Domain / Application / Infrastructure / Interfaces, null-safety | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, `data class`, `sealed class`, `companion object` 팩토리 | `docs/architecture/tactical-ddd.md` |
| Repository 인터페이스·구현, 메서드 네이밍(`find<Noun>s`/`save<Noun>`/`delete<Noun>`) | `docs/architecture/repository-pattern.md` |
| Domain Service, Technical Service (notification 모듈 예시) | `../../docs/architecture/domain-service.md` (루트 공용 문서) |
| Aggregate ID 생성 규칙 (32자리 hex, 하이픈 제거) | `docs/architecture/aggregate-id.md` |
| 전략적 설계, Subdomain, Bounded Context, Context Map, 유비쿼터스 언어 | `../../docs/architecture/strategic-ddd.md` (루트 공용 문서) |
| Spring DI 컨테이너 메커니즘, `@Component`/`@Service`/`@Repository`/`@Configuration`, `@Bean`, 순환 의존 회피 | `docs/architecture/module-pattern.md` |
| 핵심 설계 원칙 요약(치트시트), 이 구현체의 규칙 10~15개 조항 | `docs/architecture/design-principles.md` |
| 공유/공통 코드 배치, `common/`/`config/`/`auth/`/`outbox/` 패키지 컨벤션 | `docs/architecture/shared-modules.md` |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `@Transactional`, 트랜잭션 전파, Soft Delete, 마이그레이션(Flyway) | `docs/architecture/persistence.md` |
| Domain Event, Outbox 패턴, `OutboxWriter`/`OutboxPoller`/`OutboxConsumer` | `docs/architecture/domain-events.md` |
| Command/Query Service 분리, Handler 기반 CQRS 전환 기준 | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| REST 엔드포인트, `@RestController`, DTO(`data class`) | `docs/architecture/layer-architecture.md` |
| API 응답 형식, 페이지네이션, `page`/`take` | `docs/architecture/api-response.md` |
| 인증, JWT, Bearer 토큰, Spring Security Filter | `docs/architecture/authentication.md` |
| Filter / HandlerInterceptor, Correlation ID | `docs/architecture/cross-cutting-concerns.md` |
| 에러 처리, `sealed class` 예외 계층, `@RestControllerAdvice`, 에러 응답 형식 | `docs/architecture/error-handling.md` |
| 크로스 도메인 호출, Adapter 패턴 구현(가상 예시), User BC 호출 | `docs/architecture/cross-domain.md` |
| Rate Limiting, Resilience4j `RateLimiter`, 요청 속도 제한 Filter | `docs/architecture/rate-limiting.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 환경 설정, `@ConfigurationProperties`, Fail-fast 검증 | `docs/architecture/config.md` |
| Secret 관리, AWS Secrets Manager, TTL 캐시 | `docs/architecture/secret-manager.md` |
| Dockerfile, 멀티스테이지 빌드, JRE 베이스 이미지 | `docs/architecture/container.md` |
| Graceful Shutdown, `server.shutdown: graceful`, Actuator 프로브 | `docs/architecture/graceful-shutdown.md` |
| 로컬 개발 환경, docker-compose, LocalStack(SES) | `docs/architecture/local-dev.md` |
| 구조화 로깅, MDC, Correlation ID, `kotlin-logging` | `docs/architecture/observability.md` |
| 파일 업로드/다운로드, Presigned URL, AWS SDK v2 | `docs/architecture/file-storage.md` |

### 비동기 / 스케줄링

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `@Scheduled`, Task Queue(taskqueue/), Outbox Poller/Consumer, 코루틴 미사용 이유 | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Testing, Domain/Application/E2E 3계층, MockK, Testcontainers | `docs/architecture/testing.md` |
| harness 실행, 검사 규칙 목록 | `harness/README.md` |
| harness 설계 원칙(비즈니스 도메인 지식이 아닌 아키텍처 규칙만 평가) | `../../docs/harness.md` (루트 공용 문서) |
| Lint/코드 스타일, ktlint 실행 | 아래 "Lint" 절 |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 규칙 이름과 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## Lint

`examples/`에 [ktlint](https://pinterest.github.io/ktlint/) Gradle 플러그인(`org.jlleitschuh.gradle.ktlint`)이 설정되어 있다. harness는 DDD 아키텍처 규칙(파일 배치, 레이어링)만 평가하므로, import 정리·포맷팅 같은 일반적인 코드 스타일 위반은 ktlint가 담당한다.

```bash
cd examples
./gradlew ktlintFormat   # 자동 수정 (대부분의 포맷 위반)
./gradlew ktlintCheck    # 검증만 (수정하지 않고 위반 목록 출력)
```

`ktlintCheck`는 `check`/`build` 태스크에도 연결되어 있어 `./gradlew build` 실행 시 자동으로 함께 검증된다. CI(`kotlin-springboot.yml`)에도 별도 스텝으로 먼저 실행해 스타일 위반을 빠르게 실패시킨다.

기본 룰셋을 그대로 쓰되 최대 엄격도는 지양한다(`build.gradle.kts`의 `ktlint { }` 블록 참조). 자동 수정이 안 되는 위반(와일드카드 import, 파일당 단일 클래스의 파일명 불일치 등)은 `@Suppress`로 숨기지 않고 실제 코드/파일명을 고친다 — 예외가 필요하면 `docs/conventions.md`의 "주의" 각주처럼 왜 그런지 문서에 남긴다.

## 스캐폴딩 — 새 도메인 생성기

`docs/reference.md`가 정의하는 실전 구현 템플릿을 Account(Repository/Outbox 패턴)와
Card(2번째 도메인, domain/JPA 분리 구조)의 실제 코드를 조합해 만든 뒤, 도메인 이름만
파라미터화해 재사용 가능하게 일반화한 스크립트다. 단일 status 필드(PENDING/ACTIVE/CANCELLED) +
`create()`/`activate()`/`cancel(reason)` Aggregate(sealed exception 계층 포함) + Command/Query
"Service"(CQRS, `@Service` plain 클래스 — Handler/CommandBus 아님) + 도메인 이벤트 1종(`cancel()`만
발행) + Repository + JPA Entity/Mapper + REST Controller/DTO + Flyway 마이그레이션까지 한 번에
생성한다.

Kotlin/Gradle 툴체인을 새로 빌드하지 않도록 Python 스크립트로 작성했다(컴파일 없이 즉시 실행).

```bash
# 기본: ../examples/src/main/kotlin/com/example/accountservice/<domain>/ 아래 생성만 한다.
# EventHandlerRegistry.kt/GlobalExceptionHandler.kt는 건드리지 않고 붙여넣을 스니펫만 출력한다.
python3 scripts/create_domain.py Coupon

# 다른 프로젝트(스크래치 카피 등)에 생성하려면 --project-root로 Gradle 모듈 루트를 지정하고,
# EventHandlerRegistry.kt/GlobalExceptionHandler.kt 자동 패치까지 원하면 --wire를 추가한다.
python3 scripts/create_domain.py LoyaltyCategory --project-root /path/to/scratch-project/examples --wire
```

**모듈 등록 단계가 없다(대부분)** — nestjs(`@Module({ providers: [...] })`)나 Go와 달리, Spring은
`@Service`/`@Component`/`@Repository`/`@RestController`가 붙은 클래스를 classpath 전체에서
자동으로 수집한다(component scanning, `AccountServiceApplication.kt`에도 도메인 bean을 나열하는
곳이 없음을 직접 확인함). **다만 kotlin-springboot는 java-springboot와 달리 여기서 완전히 자유롭지
않다** — java-springboot의 `OutboxConsumer`는 생성자 주입 `List<OutboxEventHandler>`로
구현체를 자동 수집하지만, 이 저장소의 `outbox/EventHandlerRegistry.kt`는 각 Domain Event 핸들러를 생성자에 개별 파라미터로 명시적으로 주입받고 그
생성자에서 즉시 구성하는 `Map<eventType, handler>` 리터럴에 핸들러 항목을 직접 추가한다
(domain-events.md 3단계, 실제 코드로 확인). `common/GlobalExceptionHandler.kt`도 마찬가지로
도메인 예외마다 `@ExceptionHandler` 메서드를 손으로 등록하는 구조다. 그래서 이 두 파일만은
새 도메인마다 실제로 고쳐야 하고, `--wire` 옵션이 그 패치(import 삽입 후 재정렬 + 생성자 파라미터
추가 + `Map` 리터럴/`@ExceptionHandler` 항목 추가)를 자동화한다. `--wire` 없이 실행하면
두 파일에 붙여넣을 내용만 콘솔에 출력한다 — 기존 파일을 스크립트가 임의로 고치는 걸 원치 않을 수
있어 기본값은 안전한 쪽이다.

생성 직후 확인 순서(`<projectRoot>`는 기본값이면 `examples`, `--project-root`를 줬으면 그 경로):

```bash
bash implementations/kotlin-springboot/harness.sh <projectRoot>
cd <projectRoot> && ./gradlew ktlintCheck && ./gradlew build   # build에 ktlintCheck가 포함되어 있어 사실상 중복 확인
```

Account/Card와 무관한 새 도메인(단어 1개 "Coupon"과 다단어 "LoyaltyCategory")으로 실제 생성해
harness FAIL 0건(309건 전부 PASS), `ktlintCheck` 통과, `./gradlew build`(Testcontainers e2e
포함) 성공을 모두 확인했다. 나이브 복수형 규칙(+s/+es/자음+y→ies)을 쓰므로, 불규칙 복수형 도메인
(예: person → people)이면 `find<Domains>`/`<domains>`/REST 경로 등 생성된 이름을 수동으로
다듬어야 할 수 있다 — 실행 결과 출력이 이를 안내한다. ktlint(`standard:class-signature`,
`standard:argument-list-wrapping`, `standard:max-line-length`)는 실제 렌더링된 줄 길이로만
판정하므로, 도메인 이름 길이에 따라 한 줄/여러 줄 형태가 달라지는 부분(sealed exception의
supertype 호출, Repository 조회 분기 등)은 생성 시점에 길이를 계산해 분기하도록 스크립트 안에
구현되어 있다. 생성되는 것은 구조적 스켈레톤(빈 CRUD형 시작점)이라, 실제 비즈니스 규칙·에러
메시지·필드는 생성 후 직접 채워 넣는다.

## 예시 코드 (Account 도메인 전체)

`examples/` 디렉토리에 Account 도메인 전체 구현 예시(계좌 개설/입출금/정지/재개/종료 + SES 알림)가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

**`examples/`에 구현된 패턴**: Aggregate ID 하이픈 제거, 인증(JWT/Spring Security), Flyway 마이그레이션, 3계층 테스트(Domain/Application/E2E), 구조화 로깅·Correlation ID, Dockerfile, Secrets Manager 연동, jwt.secret의 `@ConfigurationProperties` + `@Validated` fail-fast 검증(`JwtProperties`), Graceful Shutdown/Actuator liveness·readiness 프로브(`server.shutdown: graceful` + `/actuator/health/liveness`·`/actuator/health/readiness`), Rate Limiting(Resilience4j `RateLimiter`, `RateLimitingFilter`), Soft Delete 배선, 에러 응답 4필드 구조 + 전역 예외 처리, Query Service의 읽기 전용 Repository 분리, Repository 조회/저장 메서드 네이밍(`AccountRepository`의 `findAccounts`/`saveAccount`/`deleteAccount`, [repository-pattern.md](docs/architecture/repository-pattern.md))이 모두 실제 코드로 반영되어 있다 — 각 문서(`aggregate-id.md`, `authentication.md`, `persistence.md`, `testing.md`, `observability.md`, `container.md`, `secret-manager.md`, `config.md`, `graceful-shutdown.md`, `rate-limiting.md`, `repository-pattern.md`)가 실제 코드로 명시한다.

Outbox 기반 이벤트는 `outbox/`, `account/application/event/*EventHandler.kt`로 구현되어 있다. Task Queue(정기 이자 지급, 매월 카드 사용내역 발송)는 `taskqueue/` + `account/infrastructure/scheduling/InterestPaymentScheduler.kt`/`card/infrastructure/scheduling/CardStatementScheduler.kt` + `account/interfaces/task/`/`card/interfaces/task/`로 구현되어 있다(`docs/architecture/scheduling.md` 참조). 새 코드를 작성할 때는 `examples/`의 현재 패턴이 아니라 각 문서가 정의하는 올바른 패턴을 따른다.
