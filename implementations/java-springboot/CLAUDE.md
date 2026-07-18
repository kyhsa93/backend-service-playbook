# Spring Boot 구현 가이드

DDD 기반 Java Spring Boot 서버 프로젝트의 설계/구현 가이드이다.
`<domain>/{domain,application,infrastructure,interfaces}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `../../docs/architecture/`를 참조한다.
> 이 문서는 Java Spring Boot 구현 상세에 집중한다. Kotlin Spring Boot와 Spring 프레임워크 메커니즘 자체는 상당 부분 겹치지만, 각 문서는 Java 관용(record, Lombok `@RequiredArgsConstructor`, `interfaces`(복수형) 패키지 등)으로 다시 쓰여 있다 — Kotlin 문서를 그대로 옮긴 것이 아니다.

## 작업 시 참조할 문서

### 레이어 · 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 프로젝트 구조, 패키지 레이아웃, Account/notification 모듈 실제 구조 | `docs/architecture/directory-structure.md` |
| 레이어 역할, Domain / Application / Infrastructure / Interfaces, Domain/JPA 매핑 분리 | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, `record`, 정적 팩토리 메서드 | `docs/architecture/tactical-ddd.md` |
| Repository 인터페이스·구현, 메서드 네이밍(`find<Noun>s`/`save<Noun>`/`delete<Noun>`) | `docs/architecture/repository-pattern.md` |
| Aggregate ID 생성 규칙 (32자리 hex, 하이픈 제거) | `docs/architecture/aggregate-id.md` |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `@Transactional`, 트랜잭션 전파, `REQUIRES_NEW`, Soft Delete, 마이그레이션(Flyway) | `docs/architecture/persistence.md` |
| Domain Event, Outbox 패턴, `OutboxWriter`/`OutboxRelay` | `docs/architecture/domain-events.md` |
| Command/Query Service 분리, Handler 기반 CQRS 전환 기준, 읽기 전용 Query 인터페이스 | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| REST 엔드포인트, `@RestController`, Interface DTO(`record`) | `docs/architecture/layer-architecture.md` |
| API 응답 형식, 페이지네이션, `page`/`take` | `docs/architecture/api-response.md` |
| 인증, JWT, Bearer 토큰, Spring Security `SecurityFilterChain` | `docs/architecture/authentication.md` |
| `Filter`/`HandlerInterceptor`, Correlation ID, MDC | `docs/architecture/cross-cutting-concerns.md` |
| 에러 처리, `AccountException`, `@ExceptionHandler`, 에러 응답 4필드 형식 | `docs/architecture/error-handling.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 환경 설정, `@ConfigurationProperties`, Fail-fast 검증 | `docs/architecture/config.md` |
| Secret 관리, AWS Secrets Manager, TTL 캐시 | `docs/architecture/secret-manager.md` |
| Dockerfile, 멀티스테이지 빌드, Layered JAR, JRE 베이스 이미지 | `docs/architecture/container.md` |
| Graceful Shutdown, `server.shutdown: graceful`, Actuator 프로브 | `docs/architecture/graceful-shutdown.md` |
| 로컬 개발 환경, docker-compose, LocalStack(SES) | `docs/architecture/local-dev.md` |
| 구조화 로깅, MDC, Correlation ID, Logback JSON 인코더 | `docs/architecture/observability.md` |
| 파일 업로드/다운로드, Presigned URL, AWS SDK v2 | `docs/architecture/file-storage.md` |

### 비동기 / 스케줄링

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `@Scheduled`, `@EnableScheduling`, Outbox Relay/Consumer, 다중 인스턴스 안전성 | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Testing, Domain/Application/E2E 3계층, Mockito, Testcontainers | `docs/architecture/testing.md` |
| harness 실행, 검사 규칙 목록 | `harness/README.md` |
| harness 설계 원칙(비즈니스 도메인 지식이 아닌 아키텍처 규칙만 평가) | `../../docs/harness.md` (루트 공용 문서) |

### 보너스 문서 — root에 대응 문서 없음 (NestJS 대비 6종)

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `AccountServiceApplication`, `SpringApplication.run()` 부트스트랩 순서, `application.yml` 로딩 순서, springdoc/CORS/Actuator 도입 | `docs/architecture/bootstrap.md` |
| 도메인 간 호출, Adapter 패턴 구현 예시(`application/adapter/`, `infrastructure/`), ACL | `docs/architecture/cross-domain.md` |
| 핵심 설계 원칙 13개 요약(TL;DR), 알려진 gap 인덱스 | `docs/architecture/design-principles.md` |
| `@Component`/`@Service`/`@Repository`/`@Configuration` 스테레오타입, 생성자 주입, `@Bean` 메서드, 순환 의존과 `@Lazy` | `docs/architecture/module-pattern.md` |
| Rate Limiting, Resilience4j `RateLimiter`, `Filter` 기반 구현 | `docs/architecture/rate-limiting.md` |
| 공유 코드 배치(`common/`, `config/`, `database/`, `outbox/`, `auth/`), 도메인 무관 유틸 | `docs/architecture/shared-modules.md` |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 규칙 이름과 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## 코드 포맷/린트 (Spotless)

harness는 아키텍처 규칙(파일 배치, 레이어링)만 검사하고 포맷·불필요한 import 같은 일반적인 코드 품질은
검사하지 않는다. `examples/build.gradle`에 [Spotless](https://github.com/diffplug/spotless) +
`googleJavaFormat`(AOSP 스타일, 4-space indent)을 적용해 이 gap을 메운다.

```bash
cd examples
./gradlew spotlessCheck   # 포맷 위반 검사만 (수정 없음). CI가 이 태스크를 실행한다.
./gradlew spotlessApply   # 위반 자동 수정
```

`./gradlew build`(`check` 태스크 경유)에도 `spotlessCheck`가 포함되어 있어, 포맷이 깨지면 빌드가 실패한다.
새 코드를 추가한 뒤에는 커밋 전에 `spotlessApply`를 한 번 돌리는 것을 권장한다.

## 스캐폴딩 — 새 도메인 생성기

`docs/reference.md`가 정의하는 "실전 구현 템플릿"을 Card(2번째 도메인, domain/JPA 분리 구조)와
Account(Repository/Outbox 패턴)의 실제 코드를 조합해 실제 코드로 만든 뒤, 도메인 이름만
파라미터화해 재사용 가능하게 일반화한 스크립트다. 단일 status 필드(PENDING/ACTIVE/CANCELLED) +
`create()`/`activate()`/`cancel(reason)` Aggregate + Command/Query "Service"(CQRS, `@Service`
plain 클래스 — CommandBus/Handler 아님) + 도메인 이벤트 1종 + `OutboxEventHandler` 구현체 +
Repository + JPA Entity/Mapper + REST Controller/DTO + Flyway 마이그레이션까지 한 번에
생성한다.

Java/Gradle 툴체인을 새로 빌드하지 않도록 Python 스크립트로 작성했다(Java 컴파일 없이 즉시 실행).

```bash
# 기본: ../examples/src/main/java/com/example/accountservice/<domain>/ 아래 생성
python3 scripts/create_domain.py Coupon

# 다른 프로젝트(스크래치 카피 등)에 생성하려면 --out으로 프로젝트 루트를 지정
python3 scripts/create_domain.py LoyaltyCategory --out /path/to/scratch-project
```

**모듈 등록 단계가 없다** — nestjs(`@Module({ providers: [...] })`)나 Go와 달리, Spring은
`@Service`/`@Component`/`@Repository`/`@RestController`가 붙은 클래스를 classpath 전체에서
자동으로 수집한다(component scanning). 새 도메인의 `OutboxEventHandler` 구현체도 공유
`OutboxRelay`(`outbox/OutboxRelay.java`)가 생성자 주입 `List<OutboxEventHandler>`로 자동
수집하므로, 손으로 고칠 central wiring 파일이 없다(`AccountServiceApplication.java`에도 도메인
bean을 나열하는 곳이 없음을 직접 확인함).

생성 직후 확인 순서:

```bash
cd <projectRoot>
./gradlew spotlessApply   # 템플릿 포맷을 googleJavaFormat 기준으로 맞춤
./gradlew build           # 컴파일 + 기존 테스트(Testcontainers e2e 포함) 회귀 확인
bash harness.sh <projectRoot>
```

Account/Card와 무관한 새 도메인(Coupon, LoyaltyCategory 등 다단어/불규칙 복수형 포함)으로 실제
생성해 harness FAIL 0건을 확인했다. 나이브 복수형 규칙(+s/+es/y→ies)을 쓰므로, 불규칙 복수형
도메인(예: person → people)이면 `find<Domains>`/`<domains>`/REST 경로 등 생성된 이름을 수동으로
다듬어야 할 수 있다 — 실행 결과 출력이 이를 안내한다. 생성되는 것은 구조적 스켈레톤(빈 CRUD형
시작점)이라, 실제 비즈니스 규칙·에러 메시지·필드는 생성 후 직접 채워 넣는다.

## 예시 코드 (Account 도메인 전체)

`examples/` 디렉토리에 Account 도메인 전체 구현 예시(계좌 개설/입출금/정지/재개/종료 + SES 알림)가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

**`examples/`에 구현된 패턴**: Aggregate ID 하이픈 제거, JWT 인증(Spring Security), Flyway 마이그레이션, 3계층 테스트(Domain/Application/E2E), 구조화 로깅·Correlation ID, Dockerfile(멀티스테이지), Secrets Manager 연동, Domain/JPA 레이어 분리(`AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper`), soft delete 배선(`Account.delete()` + `AccountRepository.delete()` + `DeleteAccountService`), 에러 응답 4필드 구조, Query Service의 Repository 직접 사용(`GetAccountService`), Graceful Shutdown/Actuator 프로브(`server.shutdown: graceful` + liveness/readiness), jwt.secret의 `@ConfigurationProperties` fail-fast 검증(`JwtProperties`), Rate Limiting(Resilience4j `RateLimiter`, `RateLimitFilter` + `@RateLimiter` 애노테이션 이중 방어), Dockerfile `HEALTHCHECK` 지시문, Repository 메서드 네이밍(`findAccounts`/`saveAccount`로 통일, 단건 조회는 `take:1` + 첫 결과 추출), Outbox 기반 이벤트 발행(`outbox/` 패키지)은 각 문서(`aggregate-id.md`, `authentication.md`, `persistence.md`, `testing.md`, `observability.md`, `container.md`, `secret-manager.md`, `layer-architecture.md`, `repository-pattern.md`, `error-handling.md`, `cqrs-pattern.md`, `graceful-shutdown.md`, `config.md`, `rate-limiting.md`, `domain-events.md`)가 정의하는 패턴대로 실제 코드에 반영되어 있다. 새 코드를 작성할 때는 `examples/`의 현재 패턴이 아니라 각 문서가 정의하는 올바른 패턴을 따른다.
