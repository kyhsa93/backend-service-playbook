# Testing Architecture

Organized into 3 test layers, each with a different verification scope and dependency strategy.

## Test Classification

| Layer | Verification scope | Dependency strategy | Execution speed |
|--------|----------|------------|----------|
| Domain unit tests | Aggregate, Value Object, Domain Event | No framework (pure TypeScript) | Very fast |
| Application unit tests | Command/Query Service | Mocks the Repository, Adapter | Fast |
| E2E tests | The full Controller → Service → Repository path | SQLite in-memory or testcontainers | Slow |

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
  test-database.ts                           # SQLite in-memory setup
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
      .toThrow('주문 항목은 최소 1개 이상이어야 합니다.')
  })

  it('cancel throws an error when the order is already cancelled', () => {
    const order = createOrder({ status: 'cancelled' })
    expect(() => order.cancel('변심')).toThrow('이미 취소된 주문입니다.')
  })

  it('cancel publishes an OrderCancelled event for a normal order', () => {
    const order = createOrder()
    order.cancel('변심')
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

    await expect(service.cancelOrder({ orderId: 'non-existent-id', reason: '변심' }))
      .rejects.toThrow(OrderErrorMessage['주문을 찾을 수 없습니다.'])
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

### TestDatabaseModule — SQLite In-Memory

```typescript
// test/test-database.ts
import { TypeOrmModule } from '@nestjs/typeorm'

export const TestDatabaseModule = TypeOrmModule.forRoot({
  type: 'sqlite',
  database: ':memory:',
  entities: [__dirname + '/../src/**/*.entity.ts'],
  synchronize: true  // used only in the test environment
})
```

### E2E test structure

```typescript
// test/order.e2e-spec.ts
import { INestApplication, ValidationPipe } from '@nestjs/common'
import { Test } from '@nestjs/testing'
import * as request from 'supertest'

import { OrderModule } from '@/order/order-module'
import { TestDatabaseModule } from './test-database'

describe('OrderController (e2e)', () => {
  let app: INestApplication

  beforeAll(async () => {
    const module = await Test.createTestingModule({
      imports: [TestDatabaseModule, OrderModule]
    }).compile()

    app = module.createNestApplication()
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }))
    await app.init()
  })

  it('GET /orders/:orderId — fetch an existing order', () => {
    return request(app.getHttpServer())
      .get('/orders/1')
      .set('Authorization', `Bearer ${testToken}`)
      .expect(200)
  })

  afterAll(() => app.close())
})
```

### SQLite vs testcontainers selection criteria

| Criteria | SQLite in-memory | testcontainers |
|------|-----------------|----------------|
| Speed | Fast | Slow (container startup) |
| SQL compatibility | Can't use PostgreSQL-specific syntax | Same as production |
| Setup complexity | Low | Requires Docker |
| When to prefer | Verifying simple paths that use no PostgreSQL-specific syntax | **Recommended default**; guarantees an environment identical to production |

Use testcontainers as the default. Only choose SQLite when there's no PostgreSQL-specific syntax at all and fast feedback is needed.

```typescript
// test/test-database.ts — the testcontainers version
import { TypeOrmModule } from '@nestjs/typeorm'
import { PostgreSqlContainer } from '@testcontainers/postgresql'

let container: Awaited<ReturnType<typeof new PostgreSqlContainer().start>>

export async function startTestDatabase() {
  container = await new PostgreSqlContainer().start()
  return TypeOrmModule.forRoot({
    type: 'postgres',
    url: container.getConnectionUri(),
    entities: [__dirname + '/../src/**/*.entity.ts'],
    synchronize: true
  })
}

export async function stopTestDatabase() {
  await container?.stop()
}
```

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
- **E2E tests default to testcontainers**: guarantees an environment identical to production. Only use SQLite in-memory when there's no PostgreSQL-specific syntax and speed matters.
- **Minimize mocks in E2E tests**: don't replace a module with `jest.mock()`. Replace real dependencies with nock for external HTTP and testcontainers for the DB. Mocks are for the unit-test layer only.
- **Intercept external HTTP with nock**: in E2E tests, intercept external service calls at the network boundary with nock.
- **Never connect directly to the production DB**: the test environment always uses an isolated DB.
- **No data interference between tests**: each test suite runs against independent DB state.
- **Aggregate invariant tests are required**: verify that every business rule raises an exception when violated.
