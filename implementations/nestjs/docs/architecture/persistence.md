# Database Query Patterns

### TypeORM Query Style — DB Access Only in the Repository Implementation

```typescript
// find (list) — using QueryBuilder
const qb = this.orderRepo.createQueryBuilder('order')
  .leftJoinAndSelect('order.items', 'item')
  .orderBy('order.orderId', 'DESC')
  .take(query.take)
  .skip(query.page * query.take)

if (query.status?.length) qb.andWhere('order.status IN (:...status)', { status: query.status })
if (query.keyword) qb.andWhere('order.description LIKE :keyword', { keyword: `%${query.keyword}%` })

// find + count (pagination) — key name is the plural of the domain object name
const [rows, count] = await qb.getManyAndCount()
return { orders: rows.map((row) => toDomain(row)), count }
```

### `.then()` Chaining — Preferred for Single-Record Lookup and Conversion

```typescript
// single-record lookup — the take: 1 + pop() pattern
const order = await this.orderRepository
  .findOrders({ orderId, take: 1, page: 0 })
  .then((result) => result.orders.pop())
if (!order) throw new Error(ErrorMessage['주문을 찾을 수 없습니다.'])

// update — fetch, then call the Aggregate's domain method, then save
order.cancel(reason)
await this.orderRepository.saveOrder(order)
```

### Transactions — the AsyncLocalStorage Pattern

Bind write operations spanning multiple Repositories into a single transaction. Use AsyncLocalStorage to implicitly propagate the transaction client.

#### TransactionManager (infrastructure layer)

```typescript
// database/transaction-manager.ts
import { Injectable } from '@nestjs/common'
import { DataSource, EntityManager } from 'typeorm'
import { AsyncLocalStorage } from 'async_hooks'

const transactionStorage = new AsyncLocalStorage<EntityManager>()

@Injectable()
export class TransactionManager {
  constructor(private readonly dataSource: DataSource) {}

  // runs the callback inside a transaction
  public async run<T>(fn: () => Promise<T>): Promise<T> {
    return this.dataSource.transaction((manager) =>
      transactionStorage.run(manager, fn)
    )
  }

  // returns the tx manager if there's a transaction context, otherwise the default manager
  public getManager(): EntityManager {
    return transactionStorage.getStore() ?? this.dataSource.manager
  }
}
```

#### Usage in the Repository implementation

The Repository implementation uses `this.transactionManager.getManager()` to automatically receive the propagated transaction context.

```typescript
// infrastructure/order-repository-impl.ts
@Injectable()
export class OrderRepositoryImpl extends OrderRepository {
  constructor(
    @InjectRepository(OrderEntity) private readonly orderRepo: Repository<OrderEntity>,
    private readonly transactionManager: TransactionManager
  ) {
    super()
  }

  public async saveOrder(order: Order): Promise<void> {
    const manager = this.transactionManager.getManager()
    await manager.save(OrderEntity, { ... })
  }
}
```

#### Usage in a Command Handler — actual code (transferring money between accounts)

The real use case that requires binding multiple Repository saves (specifically, two different Account instances of the same `AccountRepository`) into a single transaction is transferring money between accounts — if the withdrawal-account save and the deposit-account save each committed independently, a failure mode arises where "the withdrawal was applied but the deposit was lost." This Handler simply reuses the existing `TransactionManager` — no new infrastructure is needed.

```typescript
// application/command/transfer-command-handler.ts
@CommandHandler(TransferCommand)
export class TransferCommandHandler implements ICommandHandler<TransferCommand, TransferResult> {
  constructor(
    private readonly accountRepository: AccountRepository,
    private readonly transactionManager: TransactionManager
  ) {}

  public async execute(command: TransferCommand): Promise<TransferResult> {
    // ... load source/target, judge via TransferEligibilityService ...
    const sourceTransaction = source.withdraw(amount, transferId)
    const targetTransaction = target.deposit(amount, transferId)

    // both account saves run inside the same transaction — if either throws, everything rolls back
    await this.transactionManager.run(async () => {
      await this.accountRepository.saveAccount(source)
      await this.accountRepository.saveAccount(target)
    })

    return new TransferResult(transferId, sourceTransaction, targetTransaction)
  }
}
```

#### A single Repository call

```typescript
public async createOrder(command: CreateOrderCommand): Promise<void> {
  const order = new Order({ ... })
  await this.orderRepository.saveOrder(order)  // includes saving to the outbox internally
}
```

#### A multi-step write inside a Repository

When a single Repository implementation manipulates multiple tables internally, use `transactionManager.getManager()`. If a transaction context exists, it runs inside that transaction; otherwise it runs with the default manager.

```typescript
// infrastructure/order-repository-impl.ts
public async deleteOrder(orderId: string): Promise<void> {
  const manager = this.transactionManager.getManager()
  await manager.softDelete(OrderItemEntity, { orderId })
  await manager.softDelete(OrderEntity, { orderId })
}
```

### Dynamic Where Conditions — QueryBuilder Conditional Chaining

```typescript
const qb = this.orderRepo.createQueryBuilder('order')

if (query.userId) qb.andWhere('order.userId = :userId', { userId: query.userId })
if (query.email) qb.andWhere('order.email LIKE :email', { email: `%${query.email}%` })
if (query.name) qb.andWhere('order.name LIKE :name', { name: `%${query.name}%` })
```

### Naming Convention

- TypeORM Entity property names: use **camelCase**
- `order.orderId` (correct) / `order.order_id` (wrong)
- If the DB column name is snake_case, map it with `@Column({ name: 'order_id' })`

### Common Entity Columns — createdAt, updatedAt, deletedAt

Every TypeORM Entity includes `createdAt`, `updatedAt`, and `deletedAt` columns. Apply the common columns by extending `BaseEntity`.

```typescript
// database/base.entity.ts
import { CreateDateColumn, UpdateDateColumn, DeleteDateColumn } from 'typeorm'

export abstract class BaseEntity {
  @CreateDateColumn()
  createdAt: Date

  @UpdateDateColumn()
  updatedAt: Date

  @DeleteDateColumn()
  deletedAt: Date | null
}
```

Every Entity extends `BaseEntity`:

```typescript
// infrastructure/entity/order.entity.ts
import { Entity, PrimaryColumn, Column, OneToMany } from 'typeorm'

import { BaseEntity } from '@/database/base.entity'
import { OrderItemEntity } from '@/order/infrastructure/entity/order-item.entity'

@Entity('order')
export class OrderEntity extends BaseEntity {
  @PrimaryColumn({ type: 'char', length: 32 })
  orderId: string

  @Column({ type: 'char', length: 32 })
  userId: string

  @Column()
  status: string

  @OneToMany(() => OrderItemEntity, (item) => item.order, { cascade: true })
  items: OrderItemEntity[]
}
```

### Soft Delete

When deleting data, use soft delete — recording a timestamp in `deletedAt` — instead of an actual (hard) delete.

#### TypeORM Configuration

For an Entity with `@DeleteDateColumn()` declared, `deletedAt` is set automatically when TypeORM's `softDelete` / `softRemove` methods are used. `find`-family methods automatically apply a `deletedAt IS NULL` condition.

#### Deleting in the Repository Implementation

```typescript
// Correct — soft delete
public async deleteOrder(orderId: string): Promise<void> {
  const manager = this.transactionManager.getManager()
  await manager.softDelete(OrderEntity, { orderId })
}

// Incorrect — hard delete
public async deleteOrder(orderId: string): Promise<void> {
  const manager = this.transactionManager.getManager()
  await manager.delete(OrderEntity, { orderId })  // an actual delete — prohibited
}
```

#### When You Need to Query Deleted Data

```typescript
// query including deleted data via the withDeleted option
const qb = this.orderRepo.createQueryBuilder('order')
  .withDeleted()
  .andWhere('order.orderId = :orderId', { orderId })
```

#### Cascading Soft Delete to Child Entities

When child entities also need to be soft-deleted, handle it explicitly inside the Repository implementation:

```typescript
public async deleteOrder(orderId: string): Promise<void> {
  const manager = this.transactionManager.getManager()
  await manager.softDelete(OrderItemEntity, { orderId })
  await manager.softDelete(OrderEntity, { orderId })
}
```

`harness/evaluators/rules/soft-delete-filter.evaluator.ts` flags it as `soft-delete-filter.entity-not-soft-deletable` if a Repository implementation's (`*-repository-impl.ts`) `find` method queries an Entity without a soft-delete column (no `@DeleteDateColumn` or `BaseEntity` inheritance), and as `soft-delete-filter.raw-query-missing-filter` if raw SQL (`.query()`) queries a soft-deletable Entity without a `deletedAt IS NULL` filter (bypassing TypeORM's automatic filter).

### Migrations — the TypeORM CLI

Manage schema changes via TypeORM migrations. After modifying an Entity, generate a migration file and run it at deploy time.

#### Directory Structure

```
src/
  database/
    migrations/                      # migration files
      1712345678901-create-order.ts
      1712345678902-add-order-status.ts
    data-source.ts                   # the DataSource used by both the CLI and the app
```

#### Migration Commands

```bash
# generate a migration — auto-generated by detecting Entity changes
npx typeorm migration:generate src/database/migrations/create-order -d src/database/data-source.ts

# run migrations
npx typeorm migration:run -d src/database/data-source.ts

# roll back a migration (the last one)
npx typeorm migration:revert -d src/database/data-source.ts
```

#### Principles

- **Always generate a migration after modifying an Entity**: use `synchronize: true` only in the development environment; manage the schema via migrations in production.
- **Include migration files in the commit**: review an auto-generated file before committing it.
- **Write migrations that can be rolled back**: implement both `up()` and `down()`.
- **Data migrations go in a separate file**: don't put a schema change and a data transformation in the same migration.

`harness/evaluators/rules/no-orm-autosync-in-prod-config.evaluator.ts` flags it as `no-orm-autosync-in-prod-config.synchronize-hardcoded-true` if `synchronize` in `new DataSource({...})`/`TypeOrmModule.forRoot(Async)?({...})` is hardcoded to the literal `true`, and as `no-orm-autosync-in-prod-config.synchronize-true-in-production` if it's a conditional expression like `NODE_ENV === 'production'` that evaluates to true precisely in production.
