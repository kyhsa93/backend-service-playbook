# Testing Architecture

Organized into 3 test layers, each with a different verification scope and dependency strategy.

## Test Classification

| Layer | Verification scope | Dependency strategy | Execution speed |
|--------|----------|------------|----------|
| Domain unit tests | Aggregate, Value Object, Domain Event | No framework (pure TypeScript) | Very fast |
| Application unit tests | Command/Query Service | Mocks the Repository, Adapter | Fast |
| E2E tests | The full Controller → Service → Repository path | testcontainers (`@testcontainers/postgresql`, plus `@testcontainers/localstack` for SQS/SES paths) | Slow |

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
  support/                                   # shared E2E helpers (e.g. LocalStack SQS queue setup)
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

Verify the full use-case flow through HTTP requests.

### Test database — a real PostgreSQL via testcontainers

Every E2E spec in `test/` starts its own disposable PostgreSQL container (`@testcontainers/postgresql`) in `beforeAll` and stops it in `afterAll` — no shared in-memory database, no `test-database.ts` module. Specs that exercise the SQS/SES paths (outbox, task queue, notifications) additionally start a LocalStack container (`@testcontainers/localstack`); the shared queue-setup helpers live in `test/support/`.

```typescript
// test/auth.e2e-spec.ts — actual code (excerpt: the setup)
import { BadRequestException, INestApplication, ValidationPipe } from '@nestjs/common'
import { ConfigModule } from '@nestjs/config'
import { Test } from '@nestjs/testing'
import { TypeOrmModule } from '@nestjs/typeorm'
import { PostgreSqlContainer, StartedPostgreSqlContainer } from '@testcontainers/postgresql'
import request from 'supertest'

import { AuthModule } from '@/auth/auth-module'
import { CredentialEntity } from '@/auth/infrastructure/entity/credential.entity'
import { jwtConfig } from '@/config/jwt.config'

describe('AuthController (e2e)', () => {
  let container: StartedPostgreSqlContainer
  let app: INestApplication

  beforeAll(async () => {
    container = await new PostgreSqlContainer('postgres:16-alpine').start()

    const moduleRef = await Test.createTestingModule({
      imports: [
        ConfigModule.forRoot({ isGlobal: true, load: [jwtConfig] }),
        TypeOrmModule.forRoot({
          type: 'postgres',
          url: container.getConnectionUri(),
          entities: [CredentialEntity],
          synchronize: true
        }),
        AuthModule
      ]
    }).compile()

    app = moduleRef.createNestApplication()
    app.useGlobalPipes(new ValidationPipe({
      whitelist: true,
      transform: true,
      exceptionFactory: (errors) => {
        const message = errors.flatMap((error) => Object.values(error.constraints ?? {}))
        return new BadRequestException({ statusCode: 400, code: 'VALIDATION_FAILED', message, error: 'Bad Request' })
      }
    }))
    await app.init()
  }, 120000)

  afterAll(async () => {
    await app?.close()
    await container?.stop()
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
- **A per-spec container** keeps suites fully isolated from each other at the cost of startup time — pass a generous `beforeAll` timeout (120s here) to cover the image pull on a cold cache.
- **`synchronize: true` only in tests** — the test module builds the schema from the entity metadata; production runs migrations (see [persistence.md](persistence.md)).
- **Why not an in-memory database**: the examples use PostgreSQL-specific behavior (raw SQL with `deletedAt IS NULL` filters, `ON CONFLICT`, `char(32)` columns), so an in-memory substitute would test a different engine than production runs.

### Mocking external HTTP: nock

Intercept external HTTP calls (HttpModule, axios, etc.) in E2E tests with `nock`. Don't replace the whole module with `jest.mock()`. Mocks are for unit tests only; in E2E tests, let requests pass through the real HTTP stack and intercept only at the network boundary with nock.

```typescript
// test/order.e2e-spec.ts
import * as nock from 'nock'

afterEach(() => nock.cleanAll())

it('POST /orders — completes the order when the payment API succeeds', async () => {
  nock('https://payment.internal')
    .post('/pay')
    .reply(200, { success: true })

  return request(app.getHttpServer())
    .post('/orders')
    .send({ itemId: 'item-1', quantity: 1 })
    .expect(201)
})

it('POST /orders — returns 400 when the payment API fails', async () => {
  nock('https://payment.internal')
    .post('/pay')
    .reply(402, { error: 'insufficient_funds' })

  return request(app.getHttpServer())
    .post('/orders')
    .send({ itemId: 'item-1', quantity: 1 })
    .expect(400)
})
```

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
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' }
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
- **E2E tests run against a real PostgreSQL via testcontainers**: guarantees an environment identical to production. Specs covering SQS/SES paths add a LocalStack container.
- **Minimize mocks in E2E tests**: don't replace a module with `jest.mock()`. Replace real dependencies with nock for external HTTP and testcontainers for the DB. Mocks are for the unit-test layer only.
- **Intercept external HTTP with nock**: in E2E tests, intercept external service calls at the network boundary with nock.
- **Never connect directly to the production DB**: the test environment always uses an isolated DB.
- **No data interference between tests**: each test suite runs against independent DB state.
- **Aggregate invariant tests are required**: verify that every business rule raises an exception when violated.
