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
| Domain Event, `ApplicationEventPublisher`/`@EventListener`, Outbox 패턴 | `docs/architecture/domain-events.md` |
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
| `@Scheduled`, Outbox Relay/Consumer, 코루틴 미사용 이유 | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Testing, Domain/Application/E2E 3계층, MockK, Testcontainers | `docs/architecture/testing.md` |
| harness 실행, evaluator 규칙 목록 | `harness/README.md` |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 규칙 이름과 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## 예시 코드 (Account 도메인 전체)

`examples/` 디렉토리에 Account 도메인 전체 구현 예시(계좌 개설/입출금/정지/재개/종료 + SES 알림)가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).

**알려진 갭**: `docs/architecture/` 각 문서는 `examples/`가 아직 반영하지 않은 항목(Aggregate ID 하이픈 제거, Repository 메서드 네이밍, 에러 응답 4필드 구조, 인증, 마이그레이션, 3계층 테스트 등)을 "알려진 갭"으로 명시한다. Outbox 기반 이벤트는 이미 반영되었다(`outbox/`, `account/application/event/*EventHandler.kt`). 새 코드를 작성할 때는 `examples/`의 현재 패턴이 아니라 각 문서가 정의하는 올바른 패턴을 따른다.
