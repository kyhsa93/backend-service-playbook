# NestJS 구현 가이드

DDD 기반 NestJS TypeScript 서버 프로젝트의 설계/구현 가이드이다.
`src/<domain>/{domain,application,interface,infrastructure}/` 4레이어 구조를 따른다.

> **설계 원칙 (프레임워크 무관)** 은 루트의 [CLAUDE.md](../../CLAUDE.md) 및 `docs/architecture/`를 참조한다.
> 이 문서는 NestJS 구현 상세에 집중한다.

## 작업 시 참조할 문서

### 설계 / 프로세스

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 설계, 요구사항 분석, 전술 설계, 설계 산출물 | `docs/development-process.md` |
| 레거시 기능 수정, Vertical Slice 리팩토링 | `docs/development-process.md` (레거시 기능 수정 섹션) |
| 설계 원칙 | `docs/architecture/design-principles.md` |
| 전략적 설계, Subdomain, Bounded Context, Context Map, 유비쿼터스 언어 | `docs/architecture/strategic-ddd.md` |
| 코딩 컨벤션, 파일명 규칙, import 규칙 | `docs/conventions.md` |
| 새 도메인 추가, 도메인 모듈 템플릿, Order 예시 | `docs/reference.md` |
| 작업 완료 후 자기 검토, 체크리스트 | `docs/checklist.md` |
| Deprecated 엔드포인트 표시, @ApiOperation deprecated | `docs/conventions.md` (Deprecated 엔드포인트 섹션) |

### 레이어 · 구조

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 프로젝트 구조, 디렉토리 레이아웃 | `docs/architecture/directory-structure.md` |
| 레이어 역할, Domain / Application / Interface / Infrastructure | `docs/architecture/layer-architecture.md` |
| Repository 인터페이스·구현, abstract class | `docs/architecture/repository-pattern.md` |
| Domain Service (여러 Aggregate 조율) | `docs/architecture/domain-service.md` |
| 크로스 도메인 호출, Adapter 패턴 | `docs/architecture/cross-domain.md` |
| BC 간 통신 패턴 선택, 동기 vs 비동기, ACL, Context Map | `docs/architecture/cross-domain-communication.md` |
| Aggregate ID 생성·전파 | `docs/architecture/aggregate-id.md` |
| 공유 모듈 구조, 공용 인프라 모듈 | `docs/architecture/shared-modules.md` |
| 모듈 구성, @Module, providers, controllers, DI 바인딩 | `docs/architecture/module-pattern.md` |

### 데이터 / 트랜잭션

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| DB 쿼리, TypeORM 사용법, QueryBuilder | `docs/architecture/database-queries.md` |
| 트랜잭션 관리, TransactionManager, AsyncLocalStorage | `docs/architecture/database-queries.md` |
| 마이그레이션, synchronize, data-source | `docs/architecture/database-queries.md` |
| Domain Event, 이벤트 발행·수신, fan-out | `docs/architecture/domain-events.md` |
| Integration Event, 크로스 BC 이벤트, 공개 계약, 이벤트 버저닝 | `docs/architecture/domain-events.md` |
| Outbox 패턴, OutboxWriter, OutboxRelay, EventConsumer | `docs/architecture/domain-events.md` |
| @nestjs/cqrs, CommandBus, QueryBus, CommandHandler | `docs/architecture/cqrs-pattern.md` |

### API / Interface

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| REST 엔드포인트, Controller, DTO | `docs/architecture/module-pattern.md` |
| Swagger 문서화, @ApiProperty, @ApiOperation, @ApiTags | `docs/architecture/module-pattern.md` |
| Pagination, 공통 응답 포맷, page/take | `docs/architecture/pagination.md` |
| Rate Limiting, Throttler | `docs/architecture/rate-limiting.md` |
| 인증, 인가, Auth Guard, JWT, Bearer 토큰 | `docs/architecture/authentication.md` |
| Middleware / Guard / Interceptor / Pipe | `docs/architecture/middleware-interceptor.md` |
| 에러 처리, generateErrorResponse, 에러 메시지 enum | `docs/architecture/error-handling.md` |

### 운영 / 인프라

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| 환경 설정, 환경 변수 검증, ConfigModule | `docs/architecture/config.md` |
| Secret 관리, 비밀값 주입, secret-manager | `docs/architecture/secret-manager.md` |
| 앱 부트스트랩, main.ts, NestFactory, Swagger 설정 | `docs/architecture/bootstrap.md` |
| Graceful Shutdown, OnApplicationShutdown | `docs/architecture/graceful-shutdown.md` |
| 로컬 개발 환경, docker-compose, LocalStack, Postgres | `docs/architecture/local-dev.md` |
| Dockerfile, 멀티스테이지 빌드 | `docs/architecture/dockerfile.md` |
| Logging, 로그 레벨, structured log | `docs/architecture/logging.md` |

### 비동기 / Task Queue / Scheduling

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Scheduling, @Cron, @Interval, ScheduleModule | `docs/architecture/scheduling.md` |
| Task Queue, @TaskConsumer, Task Controller, Outbox | `docs/architecture/scheduling.md` |
| SQS, FIFO, MessageDeduplicationId, DLQ | `docs/architecture/scheduling.md` |

### 품질 / 검증

| 작업 / 키워드 | 읽을 문서 |
|---------------|----------|
| Testing, 단위 테스트, 통합 테스트, jest 설정 | `docs/architecture/testing.md` |
| harness 실행, evaluator 규칙 목록 | `harness/README.md` |

## 구현 검증

```bash
./harness.sh <projectRoot>
```

FAIL 항목의 `ruleId`와 메시지에 관련 문서 링크가 포함된다. 해당 문서를 열어 수정한다.

## 예시 코드

`examples/` 디렉토리에 Order 도메인 전체 구현 예시가 있다.
새 도메인 작업 시 이를 템플릿으로 사용한다 (도메인명만 변경).
