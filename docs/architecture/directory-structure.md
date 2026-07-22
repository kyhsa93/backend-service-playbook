# Directory Structure

Follows a domain-first, 4-layer structure. Shared infrastructure is placed outside the domain directories.

## Overall structure

```
src/
  common/                              # project-wide utilities (pure functions)
    generate-id.ts                     # generates a UUID-based unique ID
    is-unique-violation.ts             # detects a DB unique-constraint violation

  database/                            # the DB connection module
    transaction-manager.ts             # TransactionManager (AsyncLocalStorage-based)

  outbox/                              # the Outbox module
    outbox-writer.ts                   # saves events within a transaction (called from a Repository)
    outbox-relay.ts                    # Outbox → message-queue send (polling)
    event-consumer.ts                  # message queue → EventHandler receive (polling)
    event-handler-registry.ts          # eventType → Handler routing

  task-queue/                          # the Task Queue module (shared)
    task-queue.ts                      # the TaskQueue interface (abstract class)
    task-outbox-relay.ts               # task_outbox → message-queue publish (polling)
    task-consumer-registry.ts          # taskType → Handler routing
    task-queue-consumer.ts             # message queue → Task Controller dispatch (polling)

  config/
    <concern>.config.ts                # per-concern config (database, jwt, etc.)
    config-validator.ts                # env-var validation (fail-fast)

  <domain>/
    domain/                            # the Domain layer — framework-independent
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <aggregate>-repository.ts        # the Repository interface (abstract class)
    application/
      adapter/
        <external-domain>-adapter.ts   # an interface for calling an external domain (abstract class)
      service/
        <concern>-service.ts           # a technical-infrastructure interface (abstract class)
      command/
        <domain>-command-service.ts    # the Command Service (writes — uses the Repository)
        <verb>-<noun>-command.ts
      query/
        <domain>-query-service.ts      # the Query Service (reads — uses the Query interface)
        <domain>-query.ts              # the Query interface (abstract class)
        <verb>-<noun>-query.ts
        <verb>-<noun>-result.ts
      event/
        <domain-event>-handler.ts      # a Domain Event Handler (application/event/)
      integration-event/
        <name>-integration-event.ts    # an Integration Event definition (a published contract)
    interface/
      <domain>-controller.ts           # the HTTP Controller
      <domain>-task-controller.ts      # a Task Consumer (the message-queue entry point)
      dto/
        <verb>-<noun>-request-body.ts
        <verb>-<noun>-request-param.ts
        <verb>-<noun>-response-body.ts
    infrastructure/
      <aggregate>-repository-impl.ts   # the Repository implementation
      <domain>-query-impl.ts           # the Query implementation (read-only DB access)
      <external-domain>-adapter-impl.ts # the Adapter implementation
      <concern>-service-impl.ts        # a technical-infrastructure Service implementation
      <concern>-scheduler.ts           # a Scheduler (Cron → TaskQueue.enqueue)
    <domain>-error-message.ts          # the error-message enum (module root)
    <domain>-enum.ts                   # a domain enum (module root)
    <domain>-constant.ts               # domain constants (module root)
```

---

## Principles per layer

### domain/

- **Framework-independent**: import no external library at all. Pure language code.
- **Encapsulates business rules**: invariants are checked only inside an Aggregate Root's methods.
- Put **only the Repository interface (an abstract class)** here. The implementation goes in infrastructure/.

### application/

- The **coordinator** of a use case. Never carries out business logic directly — delegates to the Aggregate.
- `command/`: write use cases that use a Repository.
- `query/`: read use cases that use a Query interface.
- `adapter/`: an interface for calling an external domain (an Anticorruption Layer).
- `service/`: a technical-infrastructure interface (encryption, file storage, an external API, etc.).
- `event/`: a Domain Event Handler.
- `integration-event/`: Integration Event definitions and inbound handling.

### interface/

- The external entry point. The HTTP Controller, a Task Consumer, an Integration Event Handler.
- Converts input into a Command/Query and delegates to an Application Service.
- **Error conversion happens only here**: converts a plain Error thrown by Application into an HTTP/protocol exception.

### infrastructure/

- The only layer that actually reaches external systems.
- Every implementation of a Domain/Application abstract class lives here.
- Uses the ORM, message-queue SDK, and external API clients directly.

---

## File-naming rules

Every file name uses `kebab-case`.

| Kind | Location | File-name pattern |
|------|------|------------|
| Aggregate Root | `domain/` | `<aggregate-root>.ts` (e.g. `order.ts`) |
| Entity | `domain/` | `<entity>.ts` (e.g. `order-item.ts`) |
| Value Object | `domain/` | `<value-object>.ts` (e.g. `money.ts`) |
| Domain Event | `domain/` | `<domain-event>.ts` (e.g. `order-cancelled.ts`) |
| Repository interface | `domain/` | `<aggregate>-repository.ts` |
| Repository implementation | `infrastructure/` | `<aggregate>-repository-impl.ts` |
| Query interface | `application/query/` | `<domain>-query.ts` |
| Query implementation | `infrastructure/` | `<domain>-query-impl.ts` |
| Command Service | `application/command/` | `<domain>-command-service.ts` |
| Query Service | `application/query/` | `<domain>-query-service.ts` |
| Command | `application/command/` | `<verb>-<noun>-command.ts` |
| Query DTO | `application/query/` | `<verb>-<noun>-query.ts` |
| Result | `application/query/` | `<verb>-<noun>-result.ts` |
| Domain Event Handler | `application/event/` | `<domain-event>-handler.ts` |
| Adapter interface | `application/adapter/` | `<external-domain>-adapter.ts` |
| Adapter implementation | `infrastructure/` | `<external-domain>-adapter-impl.ts` |
| Technical-infrastructure Service interface | `application/service/` | `<concern>-service.ts` |
| Technical-infrastructure Service implementation | `infrastructure/` | `<concern>-service-impl.ts` |
| HTTP Controller | `interface/` | `<domain>-controller.ts` |
| Task Controller | `interface/` | `<domain>-task-controller.ts` |
| Scheduler | `infrastructure/` | `<concern>-scheduler.ts` |
| Error-message enum | module root | `<domain>-error-message.ts` |
| Domain enum | module root | `<domain>-enum.ts` |
| Domain constants | module root | `<domain>-constant.ts` |

---

## Class-naming rules

| Kind | Rule | Example |
|------|------|------|
| Aggregate Root | A domain noun (PascalCase) | `Order`, `User` |
| Entity | A domain noun | `OrderItem`, `Address` |
| Value Object | A domain concept | `Money`, `PhoneNumber` |
| Domain Event | Past-tense PascalCase | `OrderPlaced`, `OrderCancelled` |
| Repository interface | `<Aggregate>Repository` | `OrderRepository` |
| Repository implementation | `<Aggregate>RepositoryImpl` | `OrderRepositoryImpl` |
| Query interface | `<Domain>Query` | `OrderQuery` |
| Query implementation | `<Domain>QueryImpl` | `OrderQueryImpl` |
| Command | `<Verb><Noun>Command` | `CancelOrderCommand` |
| Query DTO | `<Verb><Noun>Query` | `GetOrdersQuery` |
| Result | `<Verb><Noun>Result` | `GetOrdersResult` |
| Adapter interface | `<ExternalDomain>Adapter` | `PaymentAdapter` |
| Adapter implementation | `<ExternalDomain>AdapterImpl` | `PaymentAdapterImpl` |
| Error Message enum | `<Domain>ErrorMessage` | `OrderErrorMessage` |

---

## Criteria for placing shared infrastructure

Shared code that lives outside `domain/` is shared infrastructure that doesn't belong to any domain.

| Directory | What it contains |
|---------|----------|
| `common/` | Pure utility functions — ID generation, DB-violation detection, etc. Framework-independent. |
| `database/` | DB connection, TransactionManager — shared by every domain's Repository |
| `outbox/` | OutboxWriter, OutboxPoller (Outbox → message-queue publish), OutboxConsumer (queue receive → run Handler), EventHandlerRegistry |
| `task-queue/` | The TaskQueue interface/implementation, the Consumer, the idempotency ledger |
| `config/` | Loading/validating environment variables. Config files split per concern. |

→ See `docs/implementations/` for how module registration works per framework

---

### Related docs

- [layer-architecture.md](layer-architecture.md) — details on the layer dependency direction and roles
- [repository-pattern.md](repository-pattern.md) — details on the Repository pattern
- [domain-events.md](domain-events.md) — the Outbox structure
- [config.md](config.md) — configuration management
