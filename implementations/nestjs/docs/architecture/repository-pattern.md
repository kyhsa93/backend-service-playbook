# Repository Pattern

### One Repository per Aggregate Root

- **1 Aggregate Root = 1 Repository interface + 1 Repository implementation**
- The interface (abstract class) lives in the `domain/` layer, the implementation in the `infrastructure/` layer.
- Child Entities inside an Aggregate are saved/retrieved together through the Aggregate Root's Repository.

```
src/
  order/
    domain/
      order-repository.ts          ← abstract class (the interface)
    infrastructure/
      order-repository-impl.ts     ← extends OrderRepository (the implementation)
```

### NestJS DI Wiring

The Module uses the abstract class as a token to inject the implementation:

```typescript
// order-module.ts
@Module({
  imports: [TypeOrmModule.forFeature([OrderEntity, OrderItemEntity])],
  controllers: [OrderController],
  providers: [
    OrderCommandService,
    OrderQueryService,
    { provide: OrderRepository, useClass: OrderRepositoryImpl },
    { provide: OrderQuery, useClass: OrderQueryImpl },
    { provide: PaymentRepository, useClass: PaymentRepositoryImpl }
  ]
})
export class OrderModule {}
```

The Service injects it by its abstract class type:

```typescript
constructor(private readonly orderRepository: OrderRepository) {}
```

### Repository Method Naming Rules

| Purpose | Method name pattern | Example |
|------|--------------|------|
| List lookup | `find<Noun>s` | `findOrders`, `findUsers` |
| Save/upsert | `save<Noun>` | `saveOrder`, `saveUser` |
| Delete | `delete<Noun>` | `deleteOrder`, `deleteUser` |

- **Queries always use a single `find<Noun>s`** — use the list-lookup method whether it's a single-record or list lookup
- For a single-record lookup, call it with `take: 1` from the Service and use the `.then(r => r.<noun>s.pop())` pattern
- **An update method on the Repository is prohibited** — fetch, modify via the Aggregate's domain method, then save via `save<Noun>`

The `find...By...` form, a bare `findAll`, a separate `count*` method, a bare `save`/`delete`, and a separate `update*` method are all
caught by `harness/evaluators/rules/repository-naming.evaluator.ts` under the `repository-naming.*` ruleIds.

### Domain Boundaries — Two-Way Mapping-Table Access

The boundary between two domains is defined as a **mapping table**.
The mapping table must be queryable/savable/deletable from **both connected domains' Repository implementations**.
Each Repository implementation accesses the mapping table using **its own domain's identifier**.

```
user ──── userGroupMap ──── group ──── groupRoleMap ──── role
   the user-side identifier: userId          the group-side identifier: groupId
   the group-side identifier: groupId         the role-side identifier: roleId
```

### Cascading Save/Delete in the Repository

When `save<Noun>` / `delete<Noun>` is called, the Repository implementation internally **handles the child entities and the connected mapping table together**.
The Service doesn't manage the cascade order directly — it just calls a single domain-level method.

```typescript
// inside infrastructure/group-repository-impl.ts
public async deleteGroup(groupId: string): Promise<void> {
  const manager = this.transactionManager.getManager()
  // FK reference order: mapping tables first → then the main entity
  await manager.softDelete(GroupRoleMapEntity, { groupId })
  await manager.softDelete(UserGroupMapEntity, { groupId })
  await manager.softDelete(GroupEntity, { groupId })
}
```
