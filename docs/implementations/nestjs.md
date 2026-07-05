# NestJS 구현체

## 개요

[NestJS](https://nestjs.com/)는 TypeScript 기반 Node.js 서버 프레임워크로, 모듈 시스템과 DI 컨테이너를 내장한다.
이 플레이북의 원칙을 NestJS에서 구체적으로 구현하는 방법은 아래 저장소를 참조한다.

**→ [nestjs-playbook](https://github.com/kyhsa93/nestjs-playbook)**

---

## NestJS-specific 구현 커버리지

| 원칙 문서 | NestJS 구현 문서 |
|---|---|
| [layer-architecture.md](../architecture/layer-architecture.md) | `docs/architecture/layer-architecture.md` — @Injectable, @Module, DI 바인딩 |
| [repository-pattern.md](../architecture/repository-pattern.md) | `docs/architecture/repository-pattern.md` — TypeORM, @InjectRepository |
| [domain-events.md](../architecture/domain-events.md) | `docs/architecture/domain-events.md` — @HandleEvent, OutboxWriter, SQS |
| [cqrs-pattern.md](../architecture/cqrs-pattern.md) | `docs/architecture/cqrs-pattern.md` — @nestjs/cqrs, CommandBus, QueryBus |
| [error-handling.md](../architecture/error-handling.md) | `docs/architecture/error-handling.md` — generateErrorResponse, @Catch, HttpException |
| [conventions.md](../conventions.md) | `docs/conventions.md` — 파일 네이밍, import 규칙, TypeScript 타이핑 패턴 |
| — | `docs/architecture/module-pattern.md` — @Module, providers, exports, 순환 의존 |
| — | `docs/architecture/bootstrap.md` — main.ts, NestFactory, Swagger |
| — | `docs/architecture/authentication.md` — JWT, AuthGuard |
| — | `docs/architecture/middleware-interceptor.md` — Guard, Interceptor, Pipe |
| — | `docs/architecture/config.md` — ConfigModule, class-validator 환경변수 검증 |
| — | `docs/architecture/database-queries.md` — TypeORM, QueryBuilder, 마이그레이션 |
| — | `docs/architecture/scheduling.md` — @Cron, SQS Task Queue, 멱등성 |
| — | `docs/architecture/testing.md` — jest, SQLite in-memory E2E 테스트 |

---

## NestJS 선택 이유

- TypeScript 네이티브 — 타입 안전성, DI 컨테이너가 데코레이터 기반으로 통합
- 모듈 시스템이 Bounded Context 경계와 자연스럽게 대응 (`1 BC = 1 NestJS Module`)
- abstract class 기반 DI 토큰이 Repository 패턴(의존성 역전)에 적합
- `@nestjs/cqrs` 패키지로 CQRS Handler 패턴을 선택적으로 적용 가능
