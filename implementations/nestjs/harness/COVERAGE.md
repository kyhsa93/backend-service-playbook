# Harness Coverage Matrix

Tracks how thoroughly `harness/` automatically verifies `docs/`'s guide rules.

## Status Criteria

| Status | Meaning |
|---|---|
| Covered | An existing evaluator automatically verifies the main rule |
| Partial | Only some core rules are automatically verified |
| Manual | Code review/design review is more appropriate than static analysis |
| Gap | It's in the guide, but the harness has no verification for it |

## Architecture guide coverage

| Guide | Main rule / concern | Auto-check | Evaluator | Status | Gap / Next action |
|---|---|---:|---|---|---|
| `directory-structure.md` | The 4-layer structure under `src/<domain>/` | ✅ | `structure` | Covered | - |
| `layer-architecture.md` | No NestJS/TypeORM dependency in the Domain layer, layer direction, Domain must not import application/infrastructure/interface, Controller must not directly import infrastructure, Aggregate state changes only via domain methods (no public setter/mutable field) | ✅ | `layer-dependency`, `import-graph`, `domain-layer-isolation`, `interface-no-infrastructure`, `aggregate-no-public-setters` | Covered | - |
| `design-principles.md` | The Application Service coordinates; business logic lives in the Domain | ⚠️ | `checklist`, `layer-dependency` | Partial | Since business-logic placement has limits under static analysis, run alongside Manual review |
| `tactical-ddd.md` (+ root shared) | Modeling Aggregate/Entity/Value Object/Domain Event; other Aggregates may only be referenced by ID (no object references) — both within a BC and across BCs | ✅ | `no-cross-aggregate-reference`, `no-cross-bc-domain-import` | Partial | The semantic correctness of the Entity/Value Object distinction is handled alongside Manual review |
| `repository-pattern.md` | Repository as an abstract class, no direct instantiation, method naming (`find<Noun>s`/`save<Noun>`/`delete<Noun>`), no separate update method | ✅ | `repository-pattern`, `repository-naming` | Covered | Transaction-related rules are a candidate for a `transaction` evaluator |
| `module-pattern.md` | `@Module` providers composition, DI registration | ✅ | `module-di-ast` | Partial | Could add verification of imports/exports boundaries |
| `cqrs-pattern.md` | command/query separation, no Repository usage in Query | ✅ | `cqrs-pattern` | Covered | Handler-level CQRS rules could be extended later |
| `../../docs/architecture/domain-service.md` (root shared) | Domain Service placement/responsibility/naming; an Aggregate never references another Aggregate directly (ID reference only) | ✅ | `domain-service`, `no-cross-aggregate-reference` | Partial | Naming-semantics verification runs alongside Manual review. `no-cross-aggregate-reference` currently narrows its scope to the `payment/` case (Payment·Refund, within the same BC) — the same principle across BC boundaries is handled by `no-cross-bc-domain-import` (see the tactical-ddd.md row) |
| `domain-events.md` | Aggregate events, Outbox, handler placement, no direct EventBus calls | ✅ | `domain-event-outbox` | Covered | Integration event policy details need fixture expansion |
| `aggregate-id.md` | The Aggregate ID value object, restricting direct primitive-id use, stripping hyphens in `generateId()` | ✅ | `aggregate-id` | Covered | - |
| `cross-domain.md` (+ root shared `cross-domain-communication.md`) | No direct dependency between domains, must go through an Adapter, Application must not directly import another BC's Repository | ✅ | `import-graph`, `no-cross-bc-repository-in-application` | Covered | Whether the Adapter implementation actually calls only the counterpart BC's Query (and doesn't reference its Repository/Service) is handled alongside Manual review |
| `shared-modules.md` | Shared module scope, preventing overuse of common modules | ⚠️ | `structure` | Partial | Candidate for a `shared-module` evaluator |
| `persistence.md` | Query/TransactionManager/migration rules, the soft-delete filter (whether a Repository find method queries a non-soft-deletable Entity, whether raw SQL bypasses the deletedAt filter), prohibiting ORM auto-schema-sync (`synchronize: true`) in production | ✅ | `database-queries`, `layer-dependency`, `repository-pattern`, `soft-delete-filter`, `no-orm-autosync-in-prod-config` | Partial | Migration-logic correctness is handled alongside Manual review |
| `error-handling.md` | Prohibits throwing HttpException/raw strings in Domain/Application, ErrorCode/response tuple rules, the 4-field error response schema (statusCode/code/message/error) | ✅ | `error-handling` | Covered | - |
| `authentication.md` | Bearer JWT, Guard, explicit public routes | ✅ | `auth` | Partial | Token-validation logic correctness is handled alongside Manual review |
| `cross-cutting-concerns.md` | Middleware/Guard/Interceptor/Pipe placement and application | ⚠️ | `dto-validation`, `bootstrap-healthcheck` | Partial | Guard/Interceptor-level application rules are handled alongside Manual review |
| `api-response.md` | Pagination DTO, common response format, limit caps, prohibiting generic keys (`result`/`data`/`items`) in list responses, Query handler/Controller returning a dedicated Result/DTO instead of the Aggregate as-is | ✅ | `pagination`, `no-generic-response-keys`, `query-handler-no-raw-aggregate` | Partial | Sort-parameter validity is handled alongside Manual review |
| `rate-limiting.md` | Rate limit guard/interceptor/module configuration, whether the guard is actually applied (registered globally or via `@UseGuards`) — preventing dead code that's defined but never applied | ✅ | `rate-limiting` | Partial | ttl/limit appropriateness is handled alongside Manual review |
| `testing.md` | Test existence, test execution, minimizing E2E mocks, use of nock/testcontainers | ✅ | `test-presence`, `test-run`, `e2e-quality` | Partial | Per-layer test patterns and testcontainers configuration quality are handled alongside Manual review |
| `observability.md` | Logger usage, no console, no empty/unhandled catch blocks, logging entirely prohibited in the Domain layer | ✅ | `logging` | Covered | Log message content/level appropriateness is handled alongside Manual review |
| `config.md` | Environment variable validation, ConfigModule setup, restricting direct process.env references | ✅ | `config-validation` | Covered | Since `config-validation.process-env-direct-access` already blocks direct `process.env` references everywhere outside `src/config/*.config.ts` (including Domain·Application), a looser rule that only prohibits it in Domain/Application while allowing Infrastructure isn't added separately (there's currently no case in the codebase of Infrastructure directly using `process.env` — it goes through a config/ function in practice) |
| `secret-manager.md` | Preventing direct env use of sensitive values, use of SecretService/SecretsManager | ✅ | `secret-manager` | Partial | Secret-usage-path fixtures need expansion |
| `bootstrap.md` | ValidationPipe, bootstrap setup, Swagger, HttpExceptionFilter | ✅ | `bootstrap-healthcheck` | Partial | Business-appropriateness of config values is handled alongside Manual review |
| `graceful-shutdown.md` | Shutdown hook, health/readiness/liveness | ✅ | `bootstrap-healthcheck` | Partial | Completeness of resource-cleanup logic is handled alongside Manual review |
| `local-dev.md` | docker-compose, LocalStack, local run configuration | ✅ | `local-dev` | Partial | LocalStack init-script logic is handled alongside Manual review |
| `container.md` | Dockerfile security/multi-stage/build rules, HEALTHCHECK existence | ✅ | `dockerfile` | Partial | Image size optimization is handled alongside Manual review |
| `scheduling.md` | Cron/Interval placement/try-catch, TaskConsumer placement/CommandService injection | ✅ | `scheduler`, `task-queue` | Covered | - |

## Non-architecture guide coverage

| Guide | Main rule / concern | Auto-check | Evaluator | Status | Gap / Next action |
|---|---|---:|---|---|---|
| `docs/checklist.md` | The mechanically-verifiable items among the post-work self-review checklist | ✅ | `checklist` | Partial | Needs evaluator sync whenever the checklist changes |
| `docs/conventions.md` | Naming, branching, commit, and doc-writing rules | ⚠️ | `file-naming`, `controller-path` | Partial | Git/PR rules are supplemented by CI or review policy |
| `docs/reference.md` | Template structure based on the Order example | ⚠️ | Multiple evaluators | Partial | Overall example consistency is handled alongside Manual review |
| `../../../docs/development-process.md` (root shared) | The agent-role-based development process | ❌ | - | Manual | A process document isn't a target for automatic verification |

## Evaluator coverage summary

| Evaluator | Main docRef | Coverage note |
|---|---|---|
| `structure` | `directory-structure.md` | Verifies the 4-layer directory structure |
| `layer-dependency` | `layer-architecture.md` | Verifies Domain-layer dependency violations |
| `repository-pattern` | `repository-pattern.md` | Verifies Repository shape and prohibits direct construction |
| `repository-naming` | `repository-pattern.md` | Verifies Repository abstract method naming (blocklist for `find...By...`/`findAll`/`count*`/bare `save`/bare `delete`/`update*`) |
| `controller-path` | `conventions.md`, API-related docs | Prohibits verb-prefixed controller paths |
| `checklist` | `checklist.md` | Composite rules based on the checklist |
| `cqrs-pattern` | `cqrs-pattern.md` | Verifies command/query separation |
| `error-handling` | `error-handling.md` | Verifies exception/error-code rules, the 4-field error response schema |
| `test-presence` | `testing.md` | Verifies test-file existence |
| `dto-validation` | `cross-cutting-concerns.md`, API-related docs | Verifies DTO validation decorators |
| `task-queue` | `scheduling.md` | Verifies the TaskConsumer pattern |
| `scheduler` | `scheduling.md` | Verifies Cron/Interval placement and exception handling |
| `deprecated-api` | API docs/Swagger rules | Verifies deprecated-API marking |
| `module-di-ast` | `module-pattern.md` | Verifies the Module providers array |
| `import-graph` | `layer-architecture.md`, `cross-domain.md` | Prohibits domain → infrastructure imports |
| `domain-layer-isolation` | `layer-architecture.md` | Prohibits domain → application/infrastructure/interface imports (regardless of own/other domain, path-based) |
| `interface-no-infrastructure` | `layer-architecture.md` | Prohibits a domain-bearing BC's Controller from directly importing infrastructure |
| `aggregate-no-public-setters` | `layer-architecture.md` | Prohibits public setters · public non-readonly fields in a domain/ class |
| `no-cross-aggregate-reference` | `../../docs/architecture/domain-service.md` (root shared) | Verifies whether Payment·Refund in `payment/domain/` reference each other as a type (only ID references allowed) |
| `no-cross-bc-repository-in-application` | `../../docs/architecture/cross-domain-communication.md` (root shared) | Verifies whether Application directly imports another BC's domain/*-repository.ts |
| `soft-delete-filter` | `persistence.md` | Verifies whether a Repository find method queries a non-soft-deletable Entity, and whether raw SQL bypasses the deletedAt filter |
| `no-generic-response-keys` | `../../docs/architecture/api-response.md` (root shared) | Verifies whether an application/interface class's list-response array field (accompanied by count) uses a generic key like result/data/items |
| `query-handler-no-raw-aggregate` | `../../docs/architecture/api-response.md` (root shared) | Verifies whether a Query interface/QueryHandler/Controller returns the Domain Aggregate as-is (e.g. `Promise<Account>`) |
| `no-cross-bc-domain-import` | `../../docs/architecture/tactical-ddd.md` (root shared) | Verifies whether `src/<bc>/domain/` directly imports another BC's domain/* (only ID references allowed) |
| `no-orm-autosync-in-prod-config` | `../../docs/architecture/persistence.md` (root shared) | Verifies whether `synchronize` in a DataSource/TypeOrmModule config is hardcoded true, or evaluates to true in production |
| `domain-event-outbox` | `domain-events.md` | Verifies Outbox/event-handler rules |
| `build` | Entire TypeScript project | Runs `tsc --noEmit` |
| `test-run` | `testing.md` | Opt-in test execution |
| `e2e-quality` | `testing.md` | Verifies prohibition of E2E jest.mock(), use of nock/testcontainers |
| `secret-manager` | `secret-manager.md`, `config.md` | Prevents direct use of sensitive env values |
| `config-validation` | `config.md` | ConfigModule validate option, restricting direct process.env references |
| `logging` | `observability.md` | Prohibits direct console use, detects empty/unhandled catch blocks, entirely prohibits logging (Logger/winston/console) in the domain/ layer |
| `auth` | `authentication.md` | Verifies @UseGuards/@Public intent marking, AuthGuard existence |
| `bootstrap-healthcheck` | `bootstrap.md`, `graceful-shutdown.md` | Verifies enableShutdownHooks, ValidationPipe requirement |
| `dockerfile` | `container.md` | Multi-stage build, direct CMD node execution, .dockerignore, HEALTHCHECK existence (recommended, medium) |
| `local-dev` | `local-dev.md` | docker-compose postgres service, healthcheck, env file |
| `rate-limiting` | `rate-limiting.md` | ThrottlerModule configuration, verifies APP_GUARD/ThrottlerGuard is actually applied (not dead code) |
| `pagination` | `api-response.md` | page/take DTO decorators, prohibits generic response keys |
| `database-queries` | `persistence.md` | Prohibits @PrimaryGeneratedColumn, requires extending BaseEntity, requires TransactionManager to exist |
| `domain-service` | `domain-service.md` (root shared, `../../docs/architecture/`) | Prohibits @Injectable() on a Domain Service |
| `aggregate-id` | `aggregate-id.md` | Prohibits @PrimaryGeneratedColumn, requires a char(32) PrimaryColumn, verifies generateId() existence and hyphen stripping |

## Guide-Harness sync policy

When adding or changing a rule at the level of `must`, `prohibited`, or `required` in the guide, the PR must include one of the following.

1. Add an evaluator that verifies that rule
2. Add a ruleId/fixture to an existing evaluator
3. Mark it explicitly as `Manual` or `Gap` in this document and state why it isn't automatically verified

If none of the above is present, the change is considered an incomplete sync between the guide and the harness.
