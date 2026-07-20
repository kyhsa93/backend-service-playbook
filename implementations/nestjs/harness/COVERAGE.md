# Harness Coverage Matrix

`harness/`가 `docs/`의 가이드 규칙을 어느 수준까지 자동 검증하는지 추적한다.

## 상태 기준

| Status | 의미 |
|---|---|
| Covered | 현재 evaluator가 주요 규칙을 자동 검증함 |
| Partial | 일부 핵심 규칙만 자동 검증함 |
| Manual | 정적 분석보다 코드 리뷰/설계 리뷰가 적합함 |
| Gap | 가이드에는 있으나 하네스 검증이 없음 |

## Architecture guide coverage

| Guide | 주요 규칙/관심사 | Auto-check | Evaluator | Status | Gap / Next action |
|---|---|---:|---|---|---|
| `directory-structure.md` | `src/<domain>/` 하위 4레이어 구조 | ✅ | `structure` | Covered | - |
| `layer-architecture.md` | Domain 레이어의 NestJS/TypeORM 의존 금지, 레이어 방향성, Domain의 application/infrastructure/interface import 금지, Controller의 infrastructure 직접 import 금지, Aggregate 상태 변경은 도메인 메서드로만(public setter/mutable field 금지) | ✅ | `layer-dependency`, `import-graph`, `domain-layer-isolation`, `interface-no-infrastructure`, `aggregate-no-public-setters` | Covered | - |
| `design-principles.md` | Application Service는 조율, 비즈니스 로직은 Domain에 위치 | ⚠️ | `checklist`, `layer-dependency` | Partial | 비즈니스 로직 위치는 정적 분석 한계가 있어 Manual 리뷰 병행 |
| `repository-pattern.md` | Repository abstract class, 직접 인스턴스화 금지, 메서드 네이밍(`find<Noun>s`/`save<Noun>`/`delete<Noun>`), 별도 update 메서드 금지 | ✅ | `repository-pattern`, `repository-naming` | Covered | transaction 관련 규칙은 `transaction` evaluator 후보 |
| `module-pattern.md` | `@Module` providers 구성, DI 등록 | ✅ | `module-di-ast` | Partial | imports/exports 경계 검증 추가 가능 |
| `cqrs-pattern.md` | command/query 분리, Query에서 Repository 미사용 | ✅ | `cqrs-pattern` | Covered | handler 단위 CQRS 규칙은 추후 확장 가능 |
| `../../docs/architecture/domain-service.md` (루트 공용) | Domain Service 위치/책임/네이밍, Aggregate는 다른 Aggregate를 직접 참조하지 않음(ID 참조만) | ✅ | `domain-service`, `no-cross-aggregate-reference` | Partial | 네이밍 의미 검증은 Manual 리뷰 병행. `no-cross-aggregate-reference`는 현재 `payment/`(Payment·Refund) 케이스로 범위를 좁힘 — 다른 BC에 유사 케이스가 생기면 일반화 검토 |
| `domain-events.md` | Aggregate event, Outbox, handler 위치, EventBus 직접 호출 금지 | ✅ | `domain-event-outbox` | Covered | integration event 세부 정책은 fixture 확장 필요 |
| `aggregate-id.md` | Aggregate ID value object, primitive id 직접 사용 제한, `generateId()`의 하이픈 제거 | ✅ | `aggregate-id` | Covered | - |
| `cross-domain.md` (+ 루트 공용 `cross-domain-communication.md`) | 도메인 간 직접 의존 금지, Adapter 경유, Application에서 타 BC Repository 직접 import 금지 | ✅ | `import-graph`, `no-cross-bc-repository-in-application` | Covered | Adapter 구현체가 실제로 상대 BC의 Query만 호출하는지(Repository·Service 미참조)까지는 Manual 리뷰 병행 |
| `shared-modules.md` | Shared module 범위, 공통 모듈 남용 방지 | ⚠️ | `structure` | Partial | `shared-module` evaluator 후보 |
| `persistence.md` | Query/TransactionManager/Migration 규칙, soft-delete 필터(Repository find 메서드가 soft-delete 불가능한 Entity를 조회하지 않는지, raw SQL이 deletedAt 필터를 우회하지 않는지) | ✅ | `database-queries`, `layer-dependency`, `repository-pattern`, `soft-delete-filter` | Partial | 마이그레이션 로직 정확성은 Manual 리뷰 병행 |
| `error-handling.md` | Domain/Application HttpException·raw 문자열 throw 금지, ErrorCode/응답 tuple 규칙, 에러 응답 4필드(statusCode/code/message/error) 스키마 | ✅ | `error-handling` | Covered | - |
| `authentication.md` | Bearer JWT, Guard, public route 명시 | ✅ | `auth` | Partial | 토큰 유효성 검증 로직은 Manual 리뷰 병행 |
| `cross-cutting-concerns.md` | Middleware/Guard/Interceptor/Pipe 위치와 적용 | ⚠️ | `dto-validation`, `bootstrap-healthcheck` | Partial | Guard/Interceptor 레벨 적용 규칙은 Manual 리뷰 병행 |
| `api-response.md` | Pagination DTO, 공통 응답 포맷, limit 제한 | ✅ | `pagination` | Partial | 정렬 파라미터 유효성은 Manual 리뷰 병행 |
| `rate-limiting.md` | Rate limit guard/interceptor/module 설정, 가드가 실제로 적용(전역 등록 또는 `@UseGuards`)됐는지 — 정의만 있고 미적용인 dead code 방지 | ✅ | `rate-limiting` | Partial | ttl/limit 적절성은 Manual 리뷰 병행 |
| `testing.md` | 테스트 존재, 테스트 실행, E2E mock 최소화, nock/testcontainers 사용 | ✅ | `test-presence`, `test-run`, `e2e-quality` | Partial | 계층별 테스트 패턴·testcontainers 설정 품질은 Manual 리뷰 병행 |
| `observability.md` | Logger 사용, console 금지, 빈/미처리 catch 블록 금지, Domain 레이어 로깅 전면 금지 | ✅ | `logging` | Covered | 로그 메시지 내용·레벨 적절성은 Manual 리뷰 병행 |
| `config.md` | 환경 변수 validation, ConfigModule 설정, process.env 직접 참조 제한 | ✅ | `config-validation` | Covered | `config-validation.process-env-direct-access`가 `src/config/*.config.ts` 외부(Domain·Application 포함) 전체의 `process.env` 직접 참조를 이미 막고 있어, Domain/Application만 금지·Infrastructure는 허용하는 더 느슨한 규칙은 별도로 추가하지 않음(현재 코드베이스에 Infrastructure의 `process.env` 직접 사용 사례 없음 — 실제로는 config/ 함수를 경유) |
| `secret-manager.md` | 민감 값 직접 env 사용 방지, SecretService/SecretsManager 사용 | ✅ | `secret-manager` | Partial | secret 사용 경로 fixture 확장 필요 |
| `bootstrap.md` | ValidationPipe, bootstrap 설정, Swagger, HttpExceptionFilter | ✅ | `bootstrap-healthcheck` | Partial | 설정값 비즈니스 적합성은 Manual 리뷰 병행 |
| `graceful-shutdown.md` | shutdown hook, health/readiness/liveness | ✅ | `bootstrap-healthcheck` | Partial | 리소스 정리 로직 완전성은 Manual 리뷰 병행 |
| `local-dev.md` | docker-compose, LocalStack, 로컬 실행 구성 | ✅ | `local-dev` | Partial | LocalStack 초기화 스크립트 로직은 Manual 리뷰 병행 |
| `container.md` | Dockerfile 보안/멀티스테이지/빌드 규칙, HEALTHCHECK 존재 | ✅ | `dockerfile` | Partial | 이미지 크기 최적화는 Manual 리뷰 병행 |
| `scheduling.md` | Cron/Interval 위치/try-catch, TaskConsumer 위치/CommandService 주입 | ✅ | `scheduler`, `task-queue` | Covered | - |

## Non-architecture guide coverage

| Guide | 주요 규칙/관심사 | Auto-check | Evaluator | Status | Gap / Next action |
|---|---|---:|---|---|---|
| `docs/checklist.md` | 작업 후 자기 검토 체크리스트 중 기계 검증 가능한 항목 | ✅ | `checklist` | Partial | 체크리스트 변경 시 evaluator 동기화 필요 |
| `docs/conventions.md` | 네이밍, 브랜치, 커밋, 문서 작성 규칙 | ⚠️ | `file-naming`, `controller-path` | Partial | Git/PR 규칙은 CI 또는 리뷰 정책으로 보완 |
| `docs/reference.md` | Order 예시 기반 템플릿 구조 | ⚠️ | 여러 evaluator | Partial | 예시 전체 일관성은 Manual 리뷰 병행 |
| `../../../docs/development-process.md` (루트 공용) | 에이전트 역할 기반 개발 프로세스 | ❌ | - | Manual | 프로세스 문서는 자동 검증 대상 아님 |

## Evaluator coverage summary

| Evaluator | 주요 docRef | Coverage note |
|---|---|---|
| `structure` | `directory-structure.md` | 4레이어 디렉토리 구조 검증 |
| `layer-dependency` | `layer-architecture.md` | Domain 레이어 의존성 위반 검증 |
| `repository-pattern` | `repository-pattern.md` | Repository 형태와 직접 생성 금지 검증 |
| `repository-naming` | `repository-pattern.md` | Repository abstract 메서드 네이밍(`find...By...`/`findAll`/`count*`/bare `save`/bare `delete`/`update*` 블록리스트) 검증 |
| `controller-path` | `conventions.md`, API 관련 문서 | 동사 prefix controller path 금지 |
| `checklist` | `checklist.md` | 체크리스트 기반 복합 룰 |
| `cqrs-pattern` | `cqrs-pattern.md` | command/query 분리 검증 |
| `error-handling` | `error-handling.md` | 예외/에러 코드 규칙, 에러 응답 4필드 스키마 검증 |
| `test-presence` | `testing.md` | 테스트 파일 존재 검증 |
| `dto-validation` | `cross-cutting-concerns.md`, API 관련 문서 | DTO validation decorator 검증 |
| `task-queue` | `scheduling.md` | TaskConsumer 패턴 검증 |
| `scheduler` | `scheduling.md` | Cron/Interval 위치와 예외 처리 검증 |
| `deprecated-api` | API 문서/Swagger 규칙 | deprecated API 표시 검증 |
| `module-di-ast` | `module-pattern.md` | Module providers 배열 검증 |
| `import-graph` | `layer-architecture.md`, `cross-domain.md` | domain → infrastructure import 금지 |
| `domain-layer-isolation` | `layer-architecture.md` | domain → application/infrastructure/interface import 금지(자기/타 도메인 불문, 경로 기반) |
| `interface-no-infrastructure` | `layer-architecture.md` | domain-bearing BC의 Controller → infrastructure 직접 import 금지 |
| `aggregate-no-public-setters` | `layer-architecture.md` | domain/ class의 public setter · public 비-readonly 필드 금지 |
| `no-cross-aggregate-reference` | `../../docs/architecture/domain-service.md` (루트 공용) | `payment/domain/`의 Payment·Refund가 서로를 타입으로 참조하는지 검증(ID 참조만 허용) |
| `no-cross-bc-repository-in-application` | `../../docs/architecture/cross-domain-communication.md` (루트 공용) | Application이 타 BC의 domain/*-repository.ts를 직접 import하는지 검증 |
| `soft-delete-filter` | `persistence.md` | Repository find 메서드가 soft-delete 불가능한 Entity를 조회하는지, raw SQL이 deletedAt 필터를 우회하는지 검증 |
| `domain-event-outbox` | `domain-events.md` | Outbox/event handler 규칙 검증 |
| `build` | 전체 TypeScript 프로젝트 | `tsc --noEmit` 실행 |
| `test-run` | `testing.md` | opt-in 테스트 실행 |
| `e2e-quality` | `testing.md` | E2E jest.mock() 금지, nock/testcontainers 사용 검증 |
| `secret-manager` | `secret-manager.md`, `config.md` | 민감 env 직접 사용 방지 |
| `config-validation` | `config.md` | ConfigModule validate 옵션, process.env 직접 참조 제한 |
| `logging` | `observability.md` | console 직접 사용 금지, 빈/미처리 catch 블록 감지, domain/ 레이어 로깅(Logger/winston/console) 전면 금지 |
| `auth` | `authentication.md` | @UseGuards/@Public 의도 표시, AuthGuard 존재 검증 |
| `bootstrap-healthcheck` | `bootstrap.md`, `graceful-shutdown.md` | enableShutdownHooks, ValidationPipe 필수 검증 |
| `dockerfile` | `container.md` | 멀티스테이지 빌드, CMD node 직접 실행, .dockerignore, HEALTHCHECK 존재(권장, medium) |
| `local-dev` | `local-dev.md` | docker-compose postgres 서비스, healthcheck, env 파일 |
| `rate-limiting` | `rate-limiting.md` | ThrottlerModule 설정, APP_GUARD/ThrottlerGuard의 실제 적용(dead code 아님) 검증 |
| `pagination` | `api-response.md` | page/take DTO 데코레이터, 범용 응답 키 금지 |
| `database-queries` | `persistence.md` | @PrimaryGeneratedColumn 금지, BaseEntity 상속, TransactionManager 존재 |
| `domain-service` | `domain-service.md` (루트 공용, `../../docs/architecture/`) | Domain Service에 @Injectable() 금지 |
| `aggregate-id` | `aggregate-id.md` | @PrimaryGeneratedColumn 금지, char(32) PrimaryColumn, generateId() 존재 및 하이픈 제거 검증 |

## Guide-Harness sync policy

가이드에 `반드시`, `금지`, `해야 한다` 수준의 규칙을 추가하거나 변경할 때 PR은 다음 중 하나를 포함해야 한다.

1. 해당 규칙을 검증하는 evaluator 추가
2. 기존 evaluator에 ruleId/fixture 추가
3. 이 문서에 `Manual` 또는 `Gap`으로 명시하고 자동 검증하지 않는 이유 작성

위 항목 중 하나도 없으면 가이드와 하네스의 동기화가 불완전한 변경으로 본다.
