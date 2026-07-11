# NestJS 구현체

## 개요

[NestJS](https://nestjs.com/)는 TypeScript 기반 Node.js 서버 프레임워크로, 모듈 시스템과 DI 컨테이너를 내장한다.
이 플레이북의 원칙을 NestJS로 구체적으로 구현한 가이드와 실행 가능한 예제는 이 저장소 안의 `implementations/nestjs/`에 있다.

**→ [implementations/nestjs/CLAUDE.md](../../implementations/nestjs/CLAUDE.md)** — NestJS 구현 상세 가이드 진입점
**→ [implementations/nestjs/examples/](../../implementations/nestjs/examples/)** — Account 도메인 전체 구현 예시
**→ [implementations/nestjs/harness/](../../implementations/nestjs/harness/)** — 가이드 준수 여부를 검증하는 자동 evaluator

---

## NestJS-specific 구현 커버리지

| 원칙 문서 (루트, 공용) | NestJS 구현 문서 |
|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | `implementations/nestjs/docs/architecture/layer-architecture.md` — @Injectable, @Module, DI 바인딩 |
| [directory-structure.md](../architecture/directory-structure.md) | `implementations/nestjs/docs/architecture/directory-structure.md` — 4계층 디렉토리 배치, 파일 네이밍 |
| [aggregate-id.md](../architecture/aggregate-id.md) | `implementations/nestjs/docs/architecture/aggregate-id.md` — generateId(), 하이픈 제거 32자리 hex |
| [repository-pattern.md](../architecture/repository-pattern.md) | `implementations/nestjs/docs/architecture/repository-pattern.md` — TypeORM, @InjectRepository, NestJS DI 연결 |
| [persistence.md](../architecture/persistence.md) | `implementations/nestjs/docs/architecture/persistence.md` — TypeORM QueryBuilder, TransactionManager(AsyncLocalStorage), 마이그레이션 |
| [domain-events.md](../architecture/domain-events.md) | `implementations/nestjs/docs/architecture/domain-events.md` — @HandleEvent, OutboxWriter, OutboxRelay, SQS |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | `implementations/nestjs/docs/architecture/cqrs-pattern.md` — @nestjs/cqrs, CommandBus, QueryBus |
| [error-handling.md](../architecture/error-handling.md) | `implementations/nestjs/docs/architecture/error-handling.md` — generateErrorResponse, HttpExceptionFilter |
| [api-response.md](../architecture/api-response.md) | `implementations/nestjs/docs/architecture/api-response.md` — page/take DTO, class-validator |
| [authentication.md](../architecture/authentication.md) | `implementations/nestjs/docs/architecture/authentication.md` — JWT, AuthGuard |
| [cross-cutting-concerns.md](../architecture/cross-cutting-concerns.md) | `implementations/nestjs/docs/architecture/cross-cutting-concerns.md` — Middleware, Guard, Interceptor, Pipe |
| [scheduling.md](../architecture/scheduling.md) | `implementations/nestjs/docs/architecture/scheduling.md` — @Cron, SQS Task Queue, 멱등성 |
| [observability.md](../architecture/observability.md) | `implementations/nestjs/docs/architecture/observability.md` — structured log, Correlation ID |
| [graceful-shutdown.md](../architecture/graceful-shutdown.md) | `implementations/nestjs/docs/architecture/graceful-shutdown.md` — enableShutdownHooks |
| [container.md](../architecture/container.md) | `implementations/nestjs/docs/architecture/container.md` — 멀티스테이지 빌드 |
| [config.md](../architecture/config.md) | `implementations/nestjs/docs/architecture/config.md` — ConfigModule, class-validator 환경변수 검증 |
| [secret-manager.md](../architecture/secret-manager.md) | `implementations/nestjs/docs/architecture/secret-manager.md` — SecretsManagerClient, TTL 캐시 |
| [local-dev.md](../architecture/local-dev.md) | `implementations/nestjs/docs/architecture/local-dev.md` — docker-compose, LocalStack |
| [file-storage.md](../architecture/file-storage.md) | `implementations/nestjs/docs/architecture/file-storage.md` — StorageService, Presigned URL, S3 |
| [tactical-ddd.md](../architecture/tactical-ddd.md) | `implementations/nestjs/docs/architecture/tactical-ddd.md` — Money(Value Object), Account(Aggregate Root) 실제 코드 |
| [testing.md](../architecture/testing.md) | `implementations/nestjs/docs/architecture/testing.md` — jest, SQLite in-memory / testcontainers E2E |
| [conventions.md](../conventions.md) | `implementations/nestjs/docs/conventions.md` — 파일 네이밍, import 규칙, TypeScript 타이핑 패턴 |
| — (NestJS 전용, 대응하는 루트 문서 없음) | `implementations/nestjs/docs/architecture/module-pattern.md` — @Module, providers, exports, 순환 의존 |
| — (NestJS 전용) | `implementations/nestjs/docs/architecture/bootstrap.md` — main.ts, NestFactory, Swagger |
| — (NestJS 전용) | `implementations/nestjs/docs/architecture/shared-modules.md` — 공유 모듈 구조 |
| — (NestJS 전용) | `implementations/nestjs/docs/architecture/design-principles.md` — 핵심 설계 원칙 요약 |
| — (NestJS 전용) | `implementations/nestjs/docs/architecture/cross-domain.md` — Adapter 패턴 구현 상세 (원칙은 [cross-domain-communication.md](../architecture/cross-domain-communication.md) 참고) |

`domain-service.md`, `cross-domain-communication.md`, `strategic-ddd.md`는 NestJS 전용 버전이 루트 문서와 순수 중복이라 제거했다 — 루트 문서를 그대로 참조한다.

---

## NestJS 선택 이유

- TypeScript 네이티브 — 타입 안전성, DI 컨테이너가 데코레이터 기반으로 통합
- 모듈 시스템이 Bounded Context 경계와 자연스럽게 대응 (`1 BC = 1 NestJS Module`)
- abstract class 기반 DI 토큰이 Repository 패턴(의존성 역전)에 적합
- `@nestjs/cqrs` 패키지로 CQRS Handler 패턴을 선택적으로 적용 가능

## 이전 이력

이 구현 가이드는 원래 별도 저장소 [nestjs-playbook](https://github.com/kyhsa93/nestjs-playbook)이었으나, 다국어 플레이북으로 확장하며 `implementations/nestjs/`로 이관했다. `nestjs-playbook` 저장소는 정리(archive) 예정이며 더 이상 최신화되지 않는다 — 최신 내용은 항상 이 저장소의 `implementations/nestjs/`를 참조한다.
