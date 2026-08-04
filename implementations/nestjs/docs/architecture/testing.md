# Testing Architecture

Organized into 3 test layers, each with a different verification scope and dependency strategy.

## Test Classification

| Layer | Verification scope | Dependency strategy | Execution speed |
|--------|----------|------------|----------|
| Domain unit tests | Aggregate, Value Object, Domain Event | No framework (pure TypeScript) | Very fast |
| Application unit tests | Command/Query Service | Mocks the Repository, Adapter | Fast |
| E2E tests | The real `AppModule` end to end (Controller → Service → Repository, Outbox/SQS, SES, LLM services) | testcontainers (`@testcontainers/postgresql` + `@testcontainers/localstack`) for infrastructure, `nock` for external HTTP (Ollama) | Slow |

## Test Directory Structure

```
src/
  order/
    domain/
      order.spec.ts                          # Domain unit test
    application/
      command/
        order-command-service.spec.ts        # Application unit test
      query/
        order-query-service.spec.ts
test/
  order.e2e-spec.ts                          # E2E test
  support/                                   # shared E2E helpers (test-app.ts boots the real AppModule; SQS queue + Ollama-stub helpers)
```

- **Domain / Application unit tests**: placed as `.spec.ts` in the same directory as the corresponding source file
- **E2E tests**: placed as `.e2e-spec.ts` in the project root's `test/` directory

## Domain Unit Tests

Written in pure TypeScript, without the framework. Doesn't use the NestJS Test module.

```typescript
// src/order/domain/order.spec.ts
import { Order } from './order'
import { OrderCancelled } from './order-cancelled'

describe('Order', () => {
  const createOrder = (overrides = {}) => new Order({
    orderId: 'order-1',
    userId: 'user-1',
    items: [{ itemId: 'item-1', quantity: 2, price: 1000 }],
    status: 'pending',
    ...overrides
  })

  it('throws an error when created with empty order items', () => {
    expect(() => createOrder({ items: [] }))
      .toThrow('An order must have at least one item.')
  })

  it('cancel throws an error when the order is already cancelled', () => {
    const order = createOrder({ status: 'cancelled' })
    expect(() => order.cancel('Change of mind')).toThrow('The order is already cancelled.')
  })

  it('cancel publishes an OrderCancelled event for a normal order', () => {
    const order = createOrder()
    order.cancel('Change of mind')
    expect(order.domainEvents).toHaveLength(1)
    expect(order.domainEvents[0]).toBeInstanceOf(OrderCancelled)
  })
})
```

### What to verify

- Invariant checks on Aggregate creation (invalid input → exception)
- State changes after a business method runs
- Whether a Domain Event was published, and its payload

## Application Unit Tests

Replace the Repository and Adapter with mocks, verifying only the Service logic.

```typescript
// src/order/application/command/order-command-service.spec.ts
import { Test } from '@nestjs/testing'

import { OrderCommandService } from './order-command-service'
import { OrderRepository } from '../../domain/order-repository'
import { TransactionManager } from '@/database/transaction-manager'
import { OrderErrorMessage } from '../../order-error-message'

describe('OrderCommandService', () => {
  let service: OrderCommandService
  let orderRepository: jest.Mocked<OrderRepository>

  beforeEach(async () => {
    const module = await Test.createTestingModule({
      providers: [
        OrderCommandService,
        {
          provide: OrderRepository,
          useValue: {
            findOrders: jest.fn(),
            saveOrder: jest.fn(),
            deleteOrder: jest.fn()
          }
        },
        {
          provide: TransactionManager,
          useValue: { run: jest.fn((fn) => fn()), getManager: jest.fn() }
        }
      ]
    }).compile()

    service = module.get(OrderCommandService)
    orderRepository = module.get(OrderRepository)
  })

  it('cancelOrder throws an error when the order does not exist', async () => {
    orderRepository.findOrders.mockResolvedValue({ orders: [], count: 0 })

    await expect(service.cancelOrder({ orderId: 'non-existent-id', reason: 'Change of mind' }))
      .rejects.toThrow(OrderErrorMessage['Order not found.'])
  })
})
```

### Mock pattern

```typescript
// type the abstract class as jest.Mocked
let orderRepository: jest.Mocked<OrderRepository>

// mock only the methods you need via useValue
{
  provide: OrderRepository,
  useValue: {
    findOrders: jest.fn(),
    saveOrder: jest.fn()
  }
}
```

- Repository: use the `jest.Mocked<AbstractClass>` pattern
- TransactionManager: mock `run` so it executes the callback immediately
- Adapter: mock the external-domain call to isolate it

## E2E Tests

Verify the full use-case flow through HTTP requests — against the REAL application, not a
per-spec hand-assembled module. Every spec boots the actual `AppModule` and applies the same
`configureApp(app)` production runs (see [bootstrap.md](bootstrap.md)), so the tests exercise
the real request pipeline (helmet, ValidationPipe, interceptors, exception filter, Throttler
guard) and the real migrations — nothing is a lookalike that can drift.

### Booting the real app — `test/support/test-app.ts`

Every E2E spec in `test/` calls the shared `startTestApp()` helper in `beforeAll` and
`stop()` in `afterAll`. Each spec still owns its infrastructure: one disposable PostgreSQL
container (`@testcontainers/postgresql`) and one LocalStack container
(`@testcontainers/localstack`, SQS + SES) per spec file.

```typescript
// test/support/test-app.ts — actual code (excerpt)
export async function startTestApp(): Promise<StartedTestApp> {
  const postgres = await new PostgreSqlContainer('postgres:16-alpine').start()
  const localstack = await new LocalstackContainer('localstack/localstack:3.0')
    .withEnvironment({ SERVICES: 'sqs,ses' })
    .start()
  const awsEndpoint = localstack.getConnectionUri()

  // Everything the real app reads from the environment, set BEFORE AppModule is imported —
  // src/database/data-source.ts calls getDatabaseUrl() at module-evaluation time, and
  // app-module.ts evaluates getThrottlerConfig() while its @Module decorator runs.
  process.env.DATABASE_URL = postgres.getConnectionUri()
  process.env.AWS_ENDPOINT_URL = awsEndpoint
  process.env.SQS_DOMAIN_EVENT_QUEUE_URL = await createDomainEventQueue(awsEndpoint)
  process.env.SQS_TASK_QUEUE_URL = await createTaskQueue(awsEndpoint)
  process.env.SES_SENDER_EMAIL = SES_SENDER_EMAIL
  process.env.OLLAMA_BASE_URL = FAKE_OLLAMA_ORIGIN
  // ... AWS credentials/region, JWT, generous THROTTLE_* limits ...

  // SES refuses to send from an unverified identity — verify the sender like production would.
  await verificationClient.send(new VerifyEmailIdentityCommand({ EmailAddress: SES_SENDER_EMAIL }))

  // Dynamic imports on purpose: each Jest test file has its own module registry, so importing
  // here — after the environment above is fully populated — is what lets data-source.ts (and
  // every config read that happens at import/decorator time) see the containers' real values.
  const { AppModule } = await import('@/app-module')
  const { configureApp } = await import('@/app-setup')

  const moduleRef = await Test.createTestingModule({ imports: [AppModule] }).compile()
  const app = moduleRef.createNestApplication({ logger: ['error', 'warn'] })
  configureApp(app)
  await app.init()

  return { app, dataSource: app.get(DataSource), awsEndpoint, stop: /* app.close() first, then containers */ }
}
```

```typescript
// test/auth.e2e-spec.ts — actual code (excerpt: what a spec's setup looks like)
describe('AuthController (e2e)', () => {
  let testApp: StartedTestApp
  let app: INestApplication

  beforeAll(async () => {
    testApp = await startTestApp()
    app = testApp.app
  }, 180000)

  afterAll(async () => {
    await testApp?.stop()
  })

  it('sign-in_after_sign-up_returns_201_and_an_access_token', async () => {
    await request(app.getHttpServer()).post('/auth/sign-up').send({ userId: 'owner-1', password: 'password123!' }).expect(201)

    const response = await request(app.getHttpServer())
      .post('/auth/sign-in')
      .send({ userId: 'owner-1', password: 'password123!' })
      .expect(201)

    expect((response.body as { accessToken: string }).accessToken).toEqual(expect.any(String))
  })
})
```

- **`import request from 'supertest'`** — a default import (`esModuleInterop`), not `import * as request`.
- **Zero `overrideProvider` calls** — no provider stubs, not even for SES: the real
  `NotificationService` sends through LocalStack SES, and specs assert the recorded
  sent-email rows plus LocalStack's own send log (`/_aws/ses`).
- **Environment before import** — `data-source.ts` reads `DATABASE_URL` when its module
  evaluates, so `AppModule` must be imported dynamically after the containers are up. A spec
  must never statically import `@/app-module` (statically importing entities or pure helpers
  is fine — only `app-module.ts` pulls in the DataSource).
- **Real migrations, no `synchronize`** — the schema comes from `migrationsRun: true` running
  the same migration files production runs (see [persistence.md](persistence.md)); there is no
  test-only schema path and no hand-picked entity list.
- **A per-spec container** keeps suites fully isolated from each other at the cost of startup
  time — pass a generous `beforeAll` timeout (180s here) to cover the image pull on a cold cache.
- **Why not an in-memory database**: the examples use PostgreSQL-specific behavior (raw SQL with `deletedAt IS NULL` filters, `ON CONFLICT`, `char(32)` columns), so an in-memory substitute would test a different engine than production runs.

### Mocking external HTTP: nock

The only external dependency that isn't a container is the self-hosted Ollama LLM — every LLM
Technical Service (`NlTransactionQueryTranslatorImpl`, `NlTransactionAnswerComposerImpl`,
`TransactionAutoCategorizerImpl`, `RefundReasonClassifierImpl`) POSTs to
`${OLLAMA_BASE_URL}/api/chat`. E2E tests intercept exactly that boundary with `nock`: the
service's real request building, schema-constrained response parsing, and fallback code all
run; only the network hop is stubbed. Don't replace the provider with `overrideProvider` or
the module with `jest.mock()` — mocks are for the unit-test layer only.

Key rules, all visible in `test/account.e2e-spec.ts` / `test/payment.e2e-spec.ts` /
`test/support/ollama-stub.ts`:

- **Scope nock to a fake origin only** — `OLLAMA_BASE_URL` is set to
  `http://ollama.test.local:11434`, and interceptors are registered against that origin.
  Never call `nock.disableNetConnect()`: the testcontainers traffic to `localhost` (Postgres,
  LocalStack, the Docker socket) must pass through untouched.
- **Route by request body** — all four services hit the same `/api/chat` path, so
  interceptors match on the system prompt in the request body (`isQueryTranslatorRequest`,
  `isAutoCategorizerRequest`, ...).
- **`persist()` for background callers** — categorization/classification runs asynchronously
  (Domain Event → Outbox → SQS → Consumer), so those calls can land while an unrelated test is
  running; a persistent interceptor keeps them from failing.
- **Unpatch on teardown** — nock patches Node's process-global `http` module and `fetch`.
  Jest sandboxes user modules per file but not core modules, so a spec that uses nock must end
  with `nock.cleanAll()` + `nock.restore()` (after `testApp.stop()`), or the leaked patch
  breaks the next spec's testcontainers calls.

```typescript
// test/account.e2e-spec.ts — actual code (excerpt: the real LLM path, stubbed at the network only)
const QUESTION = 'How much have I deposited?'
const ANSWER = 'You have deposited 10000 KRW in total.'
let translatorRequest: OllamaChatRequestBody | undefined
let composerRequest: OllamaChatRequestBody | undefined

nock(FAKE_OLLAMA_ORIGIN)
  .post('/api/chat', (body) => isQueryTranslatorRequest(body as OllamaChatRequestBody))
  .reply(200, (_uri, requestBody) => {
    translatorRequest = requestBody as unknown as OllamaChatRequestBody
    return ollamaChatReply(JSON.stringify({ type: 'DEPOSIT', fromDate: '', toDate: '' }))
  })
nock(FAKE_OLLAMA_ORIGIN)
  .post('/api/chat', (body) => isAnswerComposerRequest(body as OllamaChatRequestBody))
  .reply(200, (_uri, requestBody) => {
    composerRequest = requestBody as unknown as OllamaChatRequestBody
    return ollamaChatReply(ANSWER)
  })

const response = await request(app.getHttpServer())
  .post(`/accounts/${account.accountId}/transactions/ask`)
  .set('Authorization', authHeader(OWNER_ID))
  .send({ question: QUESTION })

expect(response.status).toBe(200)
// The stubbed LLM answer came back verbatim — the real parse path ran, not the fallback.
expect(response.body.answer).toBe(ANSWER)
// The composer prompt was grounded in the retrieved transaction, not free-floating.
expect(userPrompt(composerRequest!)).toContain('DEPOSIT 10000 KRW')
```

The graceful-degradation path is covered the same way — one test replies `500` from the fake
origin and asserts the endpoint still answers from the retrieved data instead of surfacing the
infrastructure failure.

## Jest Configuration

```typescript
// jest.config.ts
export default {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.spec\\.ts$',
  transform: { '^.+\\.(t|j)s$': 'ts-jest' },
  collectCoverageFrom: ['src/**/*.(t|j)s', '!src/**/*.entity.ts', '!src/**/*.module.ts'],
  coverageDirectory: './coverage',
  testEnvironment: 'node',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' }
}
```

```typescript
// jest.e2e.config.ts
export default {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.e2e-spec\\.ts$',
  transform: { '^.+\\.(t|j)s$': 'ts-jest' },
  testEnvironment: 'node',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' },
  testTimeout: 120000
}
```

## Test Naming

```
{domain-action}_when_{condition}_then_{expected-result}
```

```typescript
// Examples
it('placeOrder_whenStockInsufficient_thenThrowsOutOfStockException')
it('cancel_whenAlreadyCancelled_thenThrowsError')
it('getOrder_whenOrderDoesNotExist_thenReturns404')
```

## Principles

- **Write Domain tests without the framework**: create instances directly with `new Aggregate()` to test. Don't use the NestJS Test module.
- **Isolate Application tests with mocks**: replace the Repository and Adapter with mocks, verifying only the Service logic.
- **E2E tests boot the real `AppModule`**: real migrations, the real request pipeline via the shared `configureApp(app)`, a real PostgreSQL and LocalStack (SQS + SES) via testcontainers — and zero `overrideProvider` calls.
- **Minimize mocks in E2E tests**: don't replace a module with `jest.mock()` or a provider with `overrideProvider`. Replace real dependencies with nock for external HTTP and testcontainers for the DB. Mocks are for the unit-test layer only.
- **Intercept external HTTP with nock**: in E2E tests, intercept external service calls at the network boundary with nock.
- **Never connect directly to the production DB**: the test environment always uses an isolated DB.
- **No data interference between tests**: each test suite runs against independent DB state.
- **Aggregate invariant tests are required**: verify that every business rule raises an exception when violated.
