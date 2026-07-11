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
| 레이어 역할, Domain / Application / Infrastructure / Interfaces, JPA 엔티티가 도메인을 겸하는 gap | `docs/architecture/layer-architecture.md` |
| Aggregate, Entity, Value Object, `record`, 정적 팩토리 메서드 | `docs/architecture/tactical-ddd.md` |
| Repository 인터페이스·구현, 메서드 네이밍(`find<Noun>s`/`save<Noun>`/`delete<Noun>`) | `docs/architecture/repository-pattern.md` |
| Aggregate ID 생성 규칙 (32자리 hex, 하이픈 제거) | `docs/architecture/aggregate-id.md` |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| `@Transactional`, 트랜잭션 전파, `REQUIRES_NEW`, Soft Delete, 마이그레이션(Flyway) | `docs/architecture/persistence.md` |
| Domain Event, Outbox 패턴, `OutboxWriter`/`OutboxRelay` | `docs/architecture/domain-events.md` |
| Command/Query Service 분리, Handler 기반 CQRS 전환 기준, Query 인터페이스 gap | `docs/architecture/cqrs-pattern.md` |

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
| Rate Limiting, Resilience4j `RateLimiter`, `Filter` 기반 구현 (현재 미구현, forward-looking) | `docs/architecture/rate-limiting.md` |
| 공유 코드 배치(`common/`, `config/`, `database/`, `outbox/`, `auth/`), 도메인 무관 유틸 | `docs/architecture/shared-modules.md` |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 규칙 이름과 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## 예시 코드 (Account 도메인 전체)

`examples/` 디렉토리에 Account 도메인 전체 구현 예시(계좌 개설/입출금/정지/재개/종료 + SES 알림)가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

**알려진 갭**: Aggregate ID 하이픈 제거, JWT 인증(Spring Security), Flyway 마이그레이션, 3계층 테스트(Domain/Application/E2E), 구조화 로깅·Correlation ID, Dockerfile(멀티스테이지), Secrets Manager 연동, Domain/JPA 레이어 분리(`AccountJpaEntity`/`TransactionJpaEntity`/`MoneyEmbeddable` + `AccountMapper`/`TransactionMapper`), soft delete 배선(`Account.delete()` + `AccountRepository.delete()` + `DeleteAccountService`), 에러 응답 4필드 구조, Query Service의 Repository 직접 사용(`GetAccountService`), Graceful Shutdown/Actuator 프로브(`server.shutdown: graceful` + liveness/readiness)는 이미 `examples/`에 반영되었다 — 각 문서(`aggregate-id.md`, `authentication.md`, `persistence.md`, `testing.md`, `observability.md`, `container.md`, `secret-manager.md`, `layer-architecture.md`, `repository-pattern.md`, `error-handling.md`, `cqrs-pattern.md`, `graceful-shutdown.md`)가 실제 코드로 명시한다. `docs/architecture/` 각 문서가 여전히 "알려진 gap"으로 명시하는, 실제로 남아있는 항목은 다음과 같다: Repository 단건 조회 메서드 네이밍(repository-pattern.md), `GetTransactionsService`의 Query 인터페이스 미전환(cqrs-pattern.md), Dockerfile `HEALTHCHECK` 미도입(container.md). 새 코드를 작성할 때는 `examples/`의 현재 패턴이 아니라 각 문서가 정의하는 올바른 패턴을 따른다. (Outbox 기반 이벤트 발행은 더 이상 gap이 아니다 — `outbox/` 패키지로 구현되어 있다.)
