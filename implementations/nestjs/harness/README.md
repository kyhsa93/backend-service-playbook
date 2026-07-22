# Harness — nestjs-playbook guide-rule linter

A static analysis tool that applies the **mechanically verifiable items** among `docs/`'s guide rules to an external NestJS project.
Each evaluator combines the TypeScript AST, file paths, and regular expressions to detect rule violations.

## Design Principles

The design principles for the harness overall (shared across all 5 languages) follow the root [`../../../docs/harness.md`](../../../docs/harness.md) — in short: **the harness evaluates the ability to follow architectural rules, not whether the business logic is "correct."** The Account domain in `examples/` is just an illustrative sample, and no evaluator may treat knowledge of a specific business domain (e.g. account suspend/resume rules) as a hard prerequisite. Always read that document before adding a new evaluator.

## Coverage

See [`COVERAGE.md`](./COVERAGE.md) for which guide rules the harness automatically verifies.

Guide rules are divided into three categories.

- **Auto-check**: automatically verified by an evaluator
- **Manual**: confirmed via code review/design review
- **Gap**: a verification item not yet implemented in the harness

When adding or changing a rule in the guide, always do one of the following.

1. Add a new evaluator, or extend an existing one
2. Mark it explicitly as Manual or Gap in `COVERAGE.md`

This process keeps the guide and the harness in sync.

## Structure

```
harness/
  evaluators/
    rules/              18 evaluators (structure, layer-dependency, ...)
    shared/             types, score, ast-utils, penalty, workspace
    cli/run.ts          CLI entry point
  tests/
    fixtures/<name>/<case>/   regression fixtures (expected.json-based)
    run-fixtures.ts           the runner
  package.json · tsconfig.json
```

## Installation

```bash
cd harness
npm install
```

## Usage

```bash
# Evaluate an entire target NestJS project
npm run evaluate -- /path/to/your-nestjs-project

# Only specific evaluators
npm run evaluate -- /path/to/project --only=structure,layer-dependency,task-queue

# Output to a file
npm run evaluate -- /path/to/project --out=report.json
```

Output (JSON):

```json
{
  "projectRoot": "/abs/path",
  "totalScore": 87,
  "grade": "B",
  "rawScore": 267,
  "rawMax": 305,
  "runEvaluators": ["structure", "layer-dependency", "..."],
  "skippedEvaluators": ["task-queue", "scheduler"],
  "failures": [
    {
      "ruleId": "repository.abstract-class",
      "severity": "high",
      "message": "The repository must be an abstract class: src/order/domain/order-repository.ts",
      "docRef": "docs/architecture/repository-pattern.md"
    }
  ]
}
```

Each failure's `docRef` is the relative path to the guide document explaining that rule. Agents/developers open this link to figure out how to fix it.

## Evaluator List

| Name | Role | maxScore |
|------|------|----------|
| `structure` | 4-layer directories + `src/task-queue/` conditionally | 25 |
| `layer-dependency` | e.g. NestJS/TypeORM prohibited in Domain | 25 |
| `repository-pattern` | Whether it's an abstract class, no direct instantiation | 25 |
| `repository-naming` | Abstract method naming in `src/<domain>/domain/*-repository.ts` — prohibits `find...By...`/`findAll`/`count*`/bare `save`/bare `delete`/`update*` (blocklist) | 15 *(auto-gated)* |
| `controller-path` | Prohibits verb prefixes like `@Controller('create…')` | 25 |
| `checklist` | A collection of mechanical rules based on `docs/checklist.md` | 100 |
| `cqrs-pattern` | `command/`·`query/` separation, no Repository usage in Query | 25 |
| `error-handling` | Prohibits `throw new Error` with a raw string in both Domain/Application (enforces referencing an ErrorMessage enum), prohibits HttpException in Domain, requires `<domain>-error-code.ts` existence/naming/1:1 message mapping, `generateErrorResponse` 3-tuple, the global exception filter's error response object has exactly 4 fields (statusCode/code/message/error) | 25 |
| `test-presence` | `test/` or `*.spec.ts` exists | 25 |
| `dto-validation` | `class-validator` decorators attached to DTOs | 25 |
| `task-queue` | When `@TaskConsumer` is used: Interface layer, CommandService injection, etc. | 20 *(auto-gated)* |
| `scheduler` | When `@Cron`/`@Interval` is used: Infrastructure layer, try-catch | 15 *(auto-gated)* |
| `deprecated-api` | `@ApiOperation({ deprecated: true })` on deprecated/legacy paths | 10 *(auto-gated)* |
| `module-di-ast` | A providers array exists in `@Module` | 25 |
| `import-graph` | Prohibits domain → infrastructure imports | 25 |
| `domain-event-outbox` | When an Aggregate publishes an event, compliance with the Outbox module/Repository saveAll/clearEvents; prohibits creating events directly or referencing OutboxWriter in Application (except `application/event/`); `@HandleEvent` must be in `application/event/<event>-handler.ts`, `@HandleIntegrationEvent` in `interface/integration-event/<domain>-integration-event-controller.ts`; prohibits calling `EventBus.publish()` directly | 15 *(auto-gated)* |
| `build` | Runs `tsc --noEmit` (if tsconfig exists) | 25 *(auto-gated)* |
| `test-run` | Runs `npm test` (`HARNESS_ENABLE_TEST_RUN=1`) | 20 *(opt-in)* |
| `secret-manager` | Fails if a sensitive key (`*_PASSWORD` · `*_SECRET` · `*_API_KEY` · `*_TOKEN`) in `src/config/*.config.ts` is sourced only from `process.env`. Requires one of `NODE_ENV` branching · `SecretsManagerClient` · `SecretService` | 10 *(auto-gated)* |
| `e2e-quality` | When `test/*.e2e-spec.ts` exists: prohibits using `jest.mock()` (high, -4/occurrence), warns if the nock/testcontainers package is missing (medium, -2) | 20 *(auto-gated)* |
| `dockerfile` | When a `Dockerfile` exists: requires a multi-stage build (AS build), direct `CMD ["node", ...]` execution, `npm ci --omit=dev`, a `.dockerignore` file, a `HEALTHCHECK` (recommended) | 15 *(auto-gated)* |
| `local-dev` | When `docker-compose.yml` exists: a postgres service, healthcheck, env file existence | 15 *(auto-gated)* |
| `rate-limiting` | When `@nestjs/throttler` is used: `ThrottlerModule.forRoot/Async`, `APP_GUARD + ThrottlerGuard` actually applied via `@Module` providers or a controller's `@UseGuards()` (not dead code) | 10 *(auto-gated)* |
| `pagination` | When a pagination DTO (page+take fields) exists: `@Type(() => Number)` + `@IsInt()` decorators, prohibits generic response keys (data/items/result) | 15 *(auto-gated)* |
| `database-queries` | When `*.entity.ts` exists: prohibits `@PrimaryGeneratedColumn()`, requires extending `BaseEntity`, requires a `TransactionManager` file to exist | 20 *(auto-gated)* |
| `domain-service` | When `src/<domain>/domain/*-service.ts` exists: prohibits `@Injectable()` and NestJS routing decorators | 10 *(auto-gated)* |
| `aggregate-id` | When `*.entity.ts` exists: prohibits `@PrimaryGeneratedColumn()`, requires `@PrimaryColumn({ type: 'char', length: 32 })`, requires a `generateId()` function to exist and strip hyphens from `randomUUID()` (`.replace(/-/g, '')`) | 15 *(auto-gated)* |
| `logging` | Prohibits directly using `console.*`, prohibits empty/unhandled catch blocks, entirely prohibits `@nestjs/common` Logger·winston·console logging in the domain/ layer | 15 |
| `domain-layer-isolation` | Fails if `src/<domain>/domain/*.ts` (regardless of own or another domain) imports application/infrastructure/interface — path-based, broader scope than import-graph | 20 *(auto-gated)* |
| `interface-no-infrastructure` | Fails if `interface/**/*.ts` (Controller) of a domain-bearing BC directly imports `infrastructure/` — it must go through Application. A cross-cutting-concern module with no `domain/`, like `common/`, is not a target | 15 *(auto-gated)* |
| `aggregate-no-public-setters` | Fails if a class in `src/<domain>/domain/*.ts` has a public `set x(...)` accessor or a public non-readonly field | 15 *(auto-gated)* |
| `no-cross-aggregate-reference` | Fails if `payment.ts`/`refund.ts` in `src/payment/domain/` import each other as a type (named import) — only ID references are allowed | 10 *(auto-gated)* |
| `no-cross-bc-repository-in-application` | Fails if `application/**/*.ts` directly imports another BC's `domain/*-repository.ts` — must go through an Adapter (ACL) | 15 *(auto-gated)* |
| `soft-delete-filter` | Fails if a `find` method in `*-repository-impl.ts` queries an Entity that can't be soft-deleted (doesn't extend BaseEntity · has no `@DeleteDateColumn`); fails if raw SQL (`.query()`) queries a soft-deletable Entity without a `deletedAt IS NULL` filter | 15 *(auto-gated)* |
| `no-generic-response-keys` | Fails if an array field sitting alongside `count: number` in an application/interface layer class is named `result`/`data`/`items` — enforces a domain-specific plural noun | 15 |
| `query-handler-no-raw-aggregate` | Fails if the Aggregate type name dynamically extracted from a `save<Noun>(param: Type)` signature is returned as-is as the Query interface/QueryHandler/Controller's `Promise<X>` return type or as the R in `IQueryHandler<Q, R>` — enforces a dedicated Result/DTO | 15 *(auto-gated)* |
| `no-cross-bc-domain-import` | Fails if `src/<bc>/domain/*.ts` directly imports another BC's `domain/*` — other Aggregates may only be referenced by ID (no object references); extends to crossing BC boundaries too | 15 *(auto-gated)* |
| `no-orm-autosync-in-prod-config` | Fails if `synchronize` in `new DataSource({...})`/`TypeOrmModule.forRoot(Async)?({...})` is the literal `true`, or evaluates to true when `NODE_ENV === 'production'` | 10 *(auto-gated)* |
| `api-documentation` | Fails per-endpoint if `@ApiOperation` is missing a `summary`/`description`, or if no non-2xx response (`@ApiNotFoundResponse` etc., class-level ones included) is documented alongside the success response | 30 |
| `user-context-store` | Fails if a `*-controller.ts` reads `req.user`/`request.user` directly — Controllers must read the authenticated user via `UserContextStore.getRequesterId()`/`getUser()` instead | 10 |

*auto-gated*: excluded from the aggregate score with `maxScore=0` if there's no code using that feature.
*opt-in*: only runs when the environment variable is explicitly set.

## Type Checking / Regression Tests

```bash
npm run typecheck          # TypeScript verification of the evaluators
npm run test:evaluators    # regression based on tests/fixtures/
```

Regression fixture structure:

```
tests/fixtures/<evaluator>/<case>/
  src/                   (minimal NestJS source — doesn't need to compile)
  expected.json          { name, applicable, expectedFailureRuleIds }
```

## Scoring

- Each evaluator returns `{ score, maxScore }`.
- `aggregate()` **normalizes to 0-100** via `sumScore / sumMax * 100`.
- A not-applicable evaluator (maxScore=0) is excluded from the aggregate.
- Grades: A ≥ 90, B ≥ 80, C ≥ 70, D ≥ 60, F < 60.

Default severity → penalty mapping (`shared/penalty.ts`):

| severity | base penalty |
|----------|--------------|
| critical | 6 |
| high     | 4 |
| medium   | 2 |
| low      | 1 |

## CI Integration

Add to the project's `.github/workflows/`:

```yaml
- run: |
    cd /path/to/harness
    npm ci
    npm run evaluate -- ${{ github.workspace }} --out=report.json
    # check the score threshold
    node -e "if (JSON.parse(require('fs').readFileSync('report.json')).totalScore < 80) process.exit(1)"
```

## Contributing

### Adding a new evaluator

1. Write `evaluators/rules/<name>.evaluator.ts`
   - Signature: `export function evaluate<Name>(root: string): EvaluatorResult`
   - Return `{ score: 0, maxScore: 0, failures: [] }` if there's nothing applicable (auto-gate)
   - Include a `docRef` (guide path) with a failure whenever possible
   - Use `penaltyFor(severity)` from `shared/penalty.ts` for the penalty amount when possible
   - Use `shared/ast-utils.ts` (`listMethodDecorators`, `listConstructorParams`, `findClassDecorator`, etc.) if AST access is needed
2. Register it in the `EVALUATORS` map in `evaluators/cli/run.ts`
3. Add a category to the breakdown routing in `evaluators/shared/score.ts` (`architecture` / `api` / `testing` / `runtime`)
4. Write a fixture at `tests/fixtures/<name>/<case>/` (a `good` case + at least one `bad-*` case)
5. `npm run typecheck && npm run test:evaluators`

### `docRef` convention

- When a failure corresponds to a specific document, use the format `docRef: 'docs/architecture/<file>.md#<anchor>'`.
- The anchor follows GitHub's generation rule (lowercase, spaces → `-`). Non-ASCII text is supported, but an em-dash (—) is stripped, which can produce a double `--`.
- Always verify locally after writing that the actual rendered link is valid.

## Related

- Guide entry point: [`CLAUDE.md`](../CLAUDE.md) (parent root) — task/keyword → document mapping.
- Rule explanations: `docs/architecture/*.md` — the principles each evaluator verifies.
