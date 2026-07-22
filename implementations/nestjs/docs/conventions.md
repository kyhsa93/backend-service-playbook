# Coding Conventions

## 1. File Naming Rules

- All file names: `kebab-case`
- Command Service: `<domain>-command-service.ts` (placed in `application/command/`)
- Query Service: `<domain>-query-service.ts` (placed in `application/query/`)
- Query interface: `<domain>-query.ts` (placed in `application/query/`)
- Query implementation: `<domain>-query-impl.ts` (placed in `infrastructure/`)
- Module: `<domain>-module.ts` (NOT `<domain>.module.ts`)
- Controller: `<domain>-controller.ts`
- Error message: `<domain>-error-message.ts`
- Error code: `<domain>-error-code.ts` (located at the module root, 1:1 mapped with messages)
- enum: `<domain>-enum.ts` (located in the module root directory)
- Constant: `<domain>-constant.ts` (located in the module root directory)
- Aggregate Root: `<aggregate-root>.ts` (domain layer)
- Entity: `<entity>.ts` (domain layer)
- Value Object: `<value-object>.ts` (domain layer)
- Domain Event: `<domain-event>.ts` (domain layer, internal use)
- Integration Event: `<name>-integration-event.ts` (placed in `application/integration-event/`, a public external contract, includes a version suffix)
- Integration Event Controller: `<domain>-integration-event-controller.ts` (placed in `interface/integration-event/`, receives Integration Events from external BCs)
- Repository interface: `<aggregate>-repository.ts` (domain layer)
- Repository implementation: `<aggregate>-repository-impl.ts` (infrastructure layer)
- DTO: verb-first, descriptive — `get-orders-request-querystring.ts`, `create-order-request-body.ts`
- Command: `<verb>-<noun>-command.ts`
- Query object: `<verb>-<noun>-query.ts` / result: `<verb>-<noun>-result.ts` — whether the read request object is querystring-based (list) or URL-parameter-based (single), always define it as `-query.ts`. Place it in `query/`. (The verb should match the Controller method name — `get`, `find`, etc.)
- Param (write): a write request that only takes URL parameters is also defined as `<verb>-<noun>-command.ts`. Place it in `command/`.
- Adapter interface: `<external-domain>-adapter.ts` (placed in `application/adapter/`)
- Adapter implementation: `<external-domain>-adapter-impl.ts` (placed in `infrastructure/`)
- Technical infrastructure Service interface: `<concern>-service.ts` (placed in `application/service/`) — abstraction over technical infrastructure such as encryption/decryption, storage, etc.
- Technical infrastructure Service implementation: `<concern>-service-impl.ts` (placed in `infrastructure/`)
- CommandHandler (`@nestjs/cqrs`): `<verb>-<noun>-command-handler.ts`
- QueryHandler (`@nestjs/cqrs`): `<verb>-<noun>-query-handler.ts`
- EventHandler (`@nestjs/cqrs`): `<domain-event>-handler.ts` (placed in `application/event/`)
- Config file: `<concern>.config.ts` (placed in `config/`) — `database.config.ts`, `jwt.config.ts`, etc.
- Config validation: `validation.config.ts` (placed in `config/`, follows the harness's `*.config.ts` naming rule)

---

## 2. Class Naming Rules

- Command Service: `OrderCommandService`, `UserCommandService`
- Query Service: `OrderQueryService`, `UserQueryService`
- Query interface: `OrderQuery`, `UserQuery`
- Query implementation: `OrderQueryImpl`, `UserQueryImpl`
- Controller: `OrderController`, `UserController`
- Module: `OrderModule`, `UserModule`
- Aggregate Root: `Order`, `User` (domain noun)
- Value Object: `Money`, `Address`, `OrderItem`
- Domain Event: `OrderPlaced`, `OrderCancelled` (past tense, internal use)
- Integration Event: `OrderCancelledIntegrationEventV1` (past tense + `IntegrationEventV<N>` suffix; eventName literal in `order.cancelled.v1` format)
- Integration Event Controller: `OrderIntegrationEventController`, `PaymentIntegrationEventController`
- Repository interface: `OrderRepository`, `UserRepository`
- Repository implementation: `OrderRepositoryImpl`, `UserRepositoryImpl`
- Adapter interface: `UserAdapter`, `PaymentAdapter` (external domain name + Adapter)
- Adapter implementation: `UserAdapterImpl`, `PaymentAdapterImpl`
- DTO: `GetOrderRequestParam`, `GetOrdersResponseBody`, `FindUsersRequestQuerystring`
- Command: `CancelOrderCommand`, `CreateUserCommand`
- Error message enum: `OrderErrorMessage`, `UserErrorMessage`
- Error code enum: `OrderErrorCode`, `UserErrorCode` (values are fixed `SCREAMING_SNAKE_CASE` strings)
- Query result: `GetOrdersResult`, `FindUsersResult`
- CommandHandler (`@nestjs/cqrs`): `CancelOrderCommandHandler`, `CreateOrderCommandHandler`
- QueryHandler (`@nestjs/cqrs`): `GetOrdersQueryHandler`, `GetOrderQueryHandler`
- EventHandler (`@nestjs/cqrs`): `OrderCancelledHandler`, `OrderPlacedHandler`

---

## 3. Enum / Constant File Separation Rules

- **All enums and constants must be defined in a separate file** — never declared inline inside another file
- **Enums and constants used within a module go in the directory where `<domain>-module.ts` is located**
  - `<domain>-enum.ts` — all enums used by that module
  - `<domain>-constant.ts` — all constants used by that module
- Enums used in the Application layer (Query/Result/Command) are likewise defined at the module root and imported from there

```typescript
// order-enum.ts — located at the module root
export enum OrderStatus {
  PENDING = 'pending',
  PAID = 'paid',
  CANCELLED = 'cancelled'
}

// order-constant.ts — located at the module root
export const MAX_ORDER_AMOUNT = 9_999_999
export const ALLOW_ORDER_STATUS_ARRAY = ['pending', 'paid']
```

---

## 4. TypeScript Typing Patterns

### DTO / Result classes — `public readonly` required

```typescript
export class Order {
  @ApiProperty()
  public readonly orderId: string

  @ApiProperty({ nullable: true, type: String })
  public readonly description: string | null

  @ApiProperty({ nullable: true, type: Date })
  public readonly completedAt: Date | null
}
```

### Command objects — `Object.assign` constructor pattern

```typescript
export class CancelOrderCommand {
  public readonly orderId: string
  public readonly reason: string
  public readonly refundAmount?: number

  constructor(command: CancelOrderCommand) {
    Object.assign(this, command)
  }
}
```

### Literal union types — used for domain values

```typescript
public readonly status: 'pending' | 'confirmed' | 'cancelled'
public readonly result: 'success' | 'fail'
public readonly scope: 'all' | 'payment'
```

### Timezone rule — based on KST (UTC+9)

- When saving a time value to the DB, convert it from UTC to KST before saving.
- A time value read from the DB is already KST, so return it as-is without conversion.
- Do not change the server/DB timezone (TZ) setting.

```typescript
// KST conversion utility
function toKST(date: Date): Date {
  return new Date(date.getTime() + 9 * 60 * 60 * 1000)
}

// When saving — convert UTC → KST before saving
const manager = this.transactionManager.getManager()
await manager.save(OrderEntity, { createdAt: toKST(new Date()) })

// When reading — the DB value is already KST, so return it as-is
const order = await this.orderRepo.findOne({ where: { orderId } })
return order.createdAt // returned as KST, unchanged
```

```typescript
// Incorrect — saving the UTC value as-is
await manager.save(OrderEntity, { createdAt: new Date() }) // saved in UTC

// Incorrect — converting a KST value read from the DB again
return toKST(order.createdAt) // double conversion causes an 18-hour offset
```

### Null-handling rules

- DB field: `string | null` (not undefined)
- Optional parameter: use `?` (`T | undefined`)
- `any` is prohibited

### ORM — inject the TypeORM Repository and TransactionManager in the Repository implementation

```typescript
constructor(
  @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
  private readonly transactionManager: TransactionManager
) {
  super()
}
```

### Complex types — use a type alias

```typescript
type OrderWithItems = Order & { items: OrderItem[] }
```

---

## 5. REST API Endpoint Design Rules

### URL structure — resource-centric, plural nouns

A URL represents a **resource (noun), not an action (verb)**. The HTTP method expresses the action.

```
// Correct
GET    /orders              List orders
GET    /orders/:orderId     Get a single order
POST   /orders              Create an order
PUT    /orders/:orderId     Fully update an order
PATCH  /orders/:orderId     Partially update an order
DELETE /orders/:orderId     Delete an order

// Incorrect
GET    /getOrders            Don't put verbs in the URL
POST   /createOrder          Don't put verbs in the URL
GET    /order/:orderId       Singular form is prohibited — always plural
```

### HTTP methods and response codes

| Method | Purpose | Success code | Response body |
|--------|------|----------|----------|
| `GET` | Retrieve resource | 200 OK | Yes |
| `POST` | Create resource | 201 Created | Optional (created resource or empty) |
| `PUT` | Fully update resource | 200 OK | Yes |
| `PATCH` | Partially update resource | 200 OK | Yes |
| `DELETE` | Delete resource | 204 No Content | None |

### Non-CRUD actions — sub-resource or verb path

An action that's hard to express as CRUD is expressed as a **sub-resource path**.

```
POST   /orders/:orderId/cancel        Cancel an order
POST   /orders/:orderId/refund        Refund an order
POST   /users/:userId/verify-email    Verify email
POST   /payments/:paymentId/capture   Capture a payment
```

### Hierarchical relationships — nested resources

Ownership/containment relationships between resources are expressed via URL nesting. Nest only up to 2 levels; beyond that, split into a top-level resource.

```
// Correct — 2-level nesting
GET    /orders/:orderId/items                   List an order's items
GET    /orders/:orderId/items/:itemId           Get a single order item

// Incorrect — 3+ level nesting
GET    /users/:userId/orders/:orderId/items/:itemId    Excessive nesting
// → Split into a top-level resource instead
GET    /order-items/:itemId
```

### List retrieval — pagination and filtering

```
GET /orders?page=0&take=20&status=pending&status=paid
```

- Pagination: `page` (0-based), `take` (page size)
- Filter: passed via querystring
- Sort: `sort=createdAt:desc` format (when needed)

### URL naming rules

- **Plural nouns**: `/orders`, `/users`, `/payments` (singular form is prohibited)
- **kebab-case**: `/order-items`, `/payment-methods` (camelCase, snake_case prohibited)
- **Lowercase only**: `/Orders` (wrong) → `/orders` (correct)
- **No trailing slash**: `/orders/` (wrong) → `/orders` (correct)
- **No file extension**: `/orders.json` (wrong) → `/orders` (correct)

### Deprecated endpoints

Don't delete an endpoint immediately when it's slated for removal — mark it with `@ApiOperation({ deprecated: true })` to give clients time to migrate.

```typescript
@Post()
@ApiOperation({ operationId: 'createOrder', deprecated: true })
async create(@Body() body: CreateOrderRequest): Promise<OrderResponse> { ... }
```

- It's exposed as `deprecated: true` in the Swagger UI and OpenAPI spec so clients can notice it.
- Log any calls with `logger.warn()` to track remaining usage.
- Note the replacement endpoint and planned removal date in `@ApiOperation({ description })`.

---

## 6. Method Naming and Organization

### Controller methods

- Use verbs like `get`, `find`, `create`, `update`, `delete`, `reset`, `cancel`, `transfer`, etc.
- All are `public async`, with an explicit return type: `Promise<ResponseType>`
- No logic — just delegate to the Service and handle catch

### Service method ordering

1. `private readonly` fields (logger, etc.)
2. constructor (Repository injection)
3. public business methods
4. private util/helper methods

### Service method return types — always explicit

```typescript
// Correct
public async getOrder(param: { orderId: string }): Promise<GetOrderResult> { ... }
public async cancelOrder(command: CancelOrderCommand): Promise<void> { ... }

// Incorrect
public async getOrder(param: { orderId: string }) { ... }  // missing return type
```

### private environment-branching helper method

```typescript
private getPaymentApiUrl() {
  if (process.env.NODE_ENV === 'prd') return 'https://payment.api.example.com'
  return 'https://payment.dev.api.example.com'
}
```

---

## 7. Import Organization Pattern

### 2-group ordering

```typescript
// 1. External packages (@nestjs/, third-party, etc.)
import { Injectable } from '@nestjs/common'
import * as dayjs from 'dayjs'

// 2. Internal @/ alias imports (alphabetical order — by path)
import { TransactionManager } from '@/database/transaction-manager'
import { formatDate } from '@/libs/datetime'
import { GetOrdersQuery } from '@/order/application/query/get-orders-query'
import { OrderRepository } from '@/order/domain/order-repository'
import { OrderErrorMessage as ErrorMessage } from '@/order/order-error-message'
```

### No relative-path imports — absolute paths only

Depending on the project setup, use one of the following two approaches:

**Approach 1: `@/` alias (recommended)**

Use this in projects where the `"@/*": ["./src/*"]` alias is defined in `tsconfig.json`.

```typescript
// Correct
import { OrderErrorMessage } from '@/order/order-error-message'
import { OrderRepository } from '@/order/domain/order-repository'
```

**Approach 2: `src/`-based absolute path**

Use this in projects without the `@/` alias, where `"baseUrl": "./"` is set in `tsconfig.json`.

```typescript
// Correct
import { OrderErrorMessage } from 'src/order/order-error-message'
import { OrderRepository } from 'src/order/domain/order-repository'
```

**Common — incorrect**

```typescript
// Incorrect (relative path)
import { OrderErrorMessage } from '../order-error-message'
import { OrderRepository } from './domain/order-repository'
```

### Use named exports (no default export)

```typescript
// Correct
export class OrderRepository { ... }

// Incorrect
export default class OrderRepository { ... }
```

---

## 8. Swagger Documentation Pattern

Complete Swagger documentation is required on every public endpoint:

```typescript
@ApiProperty({ description: 'Order ID', type: String, nullable: false })
@ApiProperty({ nullable: true, type: String })
@ApiProperty({ nullable: true, type: Date })
```

### DTO validation — class-validator

```typescript
export class GetOrderRequestParam {
  @ApiProperty({ minLength: 1 })
  @IsString()
  @MinLength(1)
  public readonly orderId: string
}

export class CreateOrderRequestBody {
  @ApiProperty({ minLength: 1, maxLength: 200 })
  @IsString()
  @MinLength(1)
  @MaxLength(200)
  public readonly description: string

  @ApiProperty({ enum: ['standard', 'express'] })
  @IsEnum(['standard', 'express'])
  public readonly deliveryType: 'standard' | 'express'
}
```

### Querystring — handling optional fields

```typescript
export class FindOrdersRequestQuerystring {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  public readonly keyword?: string

  @ApiProperty({ minimum: 0, default: 0 })
  @Type(() => Number)
  @IsInt()
  @Min(0)
  public readonly page: number

  @ApiProperty({ minimum: 1, maximum: 100, default: 20 })
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  public readonly take: number
}
```

### `@ApiProperty` placement — Query/Result vs Interface DTO

Write `@ApiProperty` directly on the Application layer's Query/Result classes.
The Interface DTO inherits via `extends`, so no separate decorators are added there.

---

## 9. Logger Pattern

### Always declare as a class field

```typescript
private readonly logger = new Logger(OrderController.name)
```

### Structured JSON logs

```typescript
// Error log
this.logger.error(error)

// Info log (snake_case field names recommended when integrating with external monitoring)
this.logger.log({ message: 'Order completed', order_id: orderId, amount })
```

---

## 10. Comment Style

- Write business domain explanations as inline comments in the team's base language
- No JSDoc — pure `//` style only
- Break up long service methods with section comments:

```typescript
// Fetch order info from the DB
// Validate payment method
// Change order status
```

---

## 11. Commit Message Convention

Follows the [Conventional Commits](https://www.conventionalcommits.org/) spec.

### Message structure

```
<type>(<scope>): <description>

[optional body]

[optional footer(s)]
```

- **First line (header)**: required. `type(scope): description` format. Within 72 characters.
- **Body**: optional. Separated from the header by a blank line. Explain **why** the change was made, not **what** was changed.
- **Footer**: optional. Include `BREAKING CHANGE:`, PR number, issue number, etc.

### type list

| type | Description | Example |
|------|------|------|
| `feat` | Add a new feature | `feat(order): add order cancellation` |
| `fix` | Bug fix | `fix(order): fix order status not updating after payment completion` |
| `refactor` | Restructure code without changing behavior | `refactor(user): move user lookup logic to Repository` |
| `docs` | Documentation-only change | `docs: update README project structure section` |
| `test` | Add or modify tests | `test(order): add unit test for order cancellation invariant` |
| `chore` | Non-code work such as build, CI, dependencies | `chore(ci): fix deploy script` |
| `style` | Code formatting, semicolons, etc. — no behavior change | `style: fix import ordering` |
| `perf` | Performance improvement | `perf(order): optimize order list query` |

### scope rules

- Use the **service domain name** as the scope: `order`, `user`, `payment`, `auth`, etc.
- For changes spanning multiple domains, omit the scope or use a higher-level concept
- For non-code changes, use the target as the scope: `ci`, `deps`, `docker`, etc.

### description rules

- Write it in English
- Use the **descriptive** form, not the imperative: "add", "fix", "remove" (NOT "please add", "fix this")
- Don't capitalize the first letter (lowercase right after the scope)
- No trailing period

### BREAKING CHANGE

Indicate a backward-incompatible change using one of the two methods below:

```
# Method 1: BREAKING CHANGE in the footer
feat(order): change order response schema

BREAKING CHANGE: totalPrice field in GetOrderResponseBody renamed to totalAmount

# Method 2: append ! after the type
feat(order)!: change order response schema
```

### Examples

```
# New feature
feat(order): add order cancellation

# Bug fix + PR number
fix(order): fix order status not updating after payment completion (#123)

# Refactoring + body
refactor(user): move user lookup logic to Repository (#124)

Moved logic that called the ORM directly from the Service into UserRepositoryImpl.
Change made in line with the Domain layer separation policy.

# Change spanning multiple domains
refactor: unify Repository interfaces as abstract classes

# Documentation change
docs: add enum file separation rule

# Test
test(order): add unit test for order creation invariant

# BREAKING CHANGE
feat(order)!: change order response schema

BREAKING CHANGE: renamed totalPrice → totalAmount field in GetOrderResponseBody
```

---

## 12. Branch and PR Convention

### Branch naming — Conventional Branch

Branch names follow the same `type/scope/description` structure as commit messages.

```
<type>/<scope>-<short-description>
```

| type | Purpose | Example |
|------|------|------|
| `feat` | New feature development | `feat/order-cancel` |
| `fix` | Bug fix | `fix/order-status-update` |
| `refactor` | Refactoring | `refactor/user-repository-migration` |
| `docs` | Documentation change | `docs/architecture-adapter-pattern` |
| `test` | Add/modify tests | `test/order-cancel-invariant` |
| `chore` | Build, CI, dependencies | `chore/ci-deploy-script` |

**Rules:**
- Every word is written in `kebab-case`.
- Omit the scope if it isn't needed: `docs/conventional-commits-guide`
- Branch off from `main`.
- Never commit/push directly to the `main` branch.

### PR workflow

```
1. Create a new branch from main
   git checkout main && git pull origin main
   git checkout -b <type>/<scope>-<short-description>

2. Commit your work (Conventional Commits format)
   git add <files>
   git commit -m "<type>(<scope>): <description>"

3. Push to remote
   git push -u origin <branch-name>

4. Create a PR against main
   gh pr create --base main --title "<type>(<scope>): <description>" --body "..."
```

### PR title

Write the PR title in the same format as Conventional Commits:

```
feat(order): add order cancellation
fix(order): fix order status not updating after payment completion
docs: add Adapter pattern guide
```

### PR body

```markdown
## Summary
- Summarize the changes in 1-3 lines

## Test plan
- [ ] Test item 1
- [ ] Test item 2
```

### Merge strategy

- Use **Squash and merge** by default. Keeps commit history clean.
- Automatically delete the remote branch after merging.

---

## 13. Test Patterns

### Unit tests — Domain layer (Aggregate, Value Object)

Domain layer unit tests are written in pure TypeScript, without the framework.

```typescript
// order/domain/order.spec.ts
describe('Order', () => {
  it('throws an error on creation when order items are empty', () => {
    expect(() => new Order({
      orderId: 'order-1',
      userId: 'user-1',
      items: [],
      status: 'pending'
    })).toThrow('An order must have at least one item.')
  })

  it('throws an error when cancelling an already-cancelled order', () => {
    const order = new Order({
      orderId: 'order-1',
      userId: 'user-1',
      items: [{ itemId: 1, quantity: 2 }],
      status: 'cancelled'
    })
    expect(() => order.cancel('Change of mind')).toThrow('The order is already cancelled.')
  })

  it('publishes an OrderCancelled event when an order is cancelled', () => {
    const order = new Order({
      orderId: 'order-1',
      userId: 'user-1',
      items: [{ itemId: 1, quantity: 2 }],
      status: 'pending'
    })
    order.cancel('Change of mind')
    expect(order.domainEvents).toHaveLength(1)
    expect(order.domainEvents[0]).toBeInstanceOf(OrderCancelled)
  })
})
```

### Unit tests — Application Service

Application Service tests replace the Repository with a mock.

```typescript
// order/application/command/order-command-service.spec.ts
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

  it('throws an error when the order does not exist', async () => {
    orderRepository.findOrders.mockResolvedValue({ orders: [], count: 0 })

    await expect(service.cancelOrder({ orderId: 'non-existent-id', reason: 'Change of mind' }))
      .rejects.toThrow(OrderErrorMessage['Order not found.'])
  })
})
```

### E2E tests — Controller layer

```typescript
// test/order.e2e-spec.ts
describe('OrderController (e2e)', () => {
  let app: INestApplication

  beforeAll(async () => {
    const module = await Test.createTestingModule({
      imports: [AppModule]
    }).compile()

    app = module.createNestApplication()
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

### Test DB configuration — SQLite in-memory

E2E tests and integration tests use a **SQLite in-memory DB** to isolate the test environment.

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

```typescript
// test/order.e2e-spec.ts
describe('OrderController (e2e)', () => {
  let app: INestApplication

  beforeAll(async () => {
    const module = await Test.createTestingModule({
      imports: [
        TestDatabaseModule,  // use SQLite in-memory instead of a real DB
        OrderModule
      ]
    }).compile()

    app = module.createNestApplication()
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }))
    await app.init()
  })

  afterAll(() => app.close())
})
```

**Principles:**
- E2E/integration tests use a SQLite in-memory DB with `synchronize: true`.
- The DB is reset for every test, so there's no data interference between tests.
- If SQL differences between the production DB and SQLite become a problem, use **testcontainers** against a real DB.

### Test naming pattern

```
{domain-action}_when_{condition}_then_{expected-result}
e.g.: placeOrder_whenStockInsufficient_thenThrowsOutOfStockException
```

---

## 14. Lint (ESLint)

`examples/` and `harness/` are each independent npm projects, each with their own ESLint (flat config, `eslint.config.mjs`). They use `eslint:recommended` + `typescript-eslint`'s `recommended` (non-type-checked) preset — the goal is baseline code-quality coverage (unused variables/imports, explicit `any`, etc.) rather than excessive strictness (`strict`/`strictTypeChecked`).

```bash
cd implementations/nestjs/examples && npm run lint
cd implementations/nestjs/harness && npm run lint
```

- `tests/fixtures/**` in the harness are **intentionally incorrect** code samples used for the harness's own tests. They're excluded via `ignores` in `eslint.config.mjs`, and lint violations there are left unfixed.
- After adding new code or modifying the harness/`examples`, run `npm run lint` first to confirm there are no baseline code-quality violations before running `npm run build && npm test && npm run test:e2e` (examples) or `npm run test:evaluators` (harness).
- CI (`.github/workflows/nestjs.yml`) also runs `npm run lint` for each project as a separate step, failing the build on violations.
