# Directory Structure

```
src/
  common/                              # shared utilities
    is-unique-violation.ts             # detects a Postgres unique_violation (23505)
  database/                            # shared database code (the actual code has no separate @Global module or BaseEntity)
    data-source.ts                     # the TypeORM DataSource config — shared with the CLI migrations
    transaction-manager.ts             # the transaction manager (AsyncLocalStorage-based)
  outbox/                              # shared Outbox code (the @Global OutboxModule) — the single path shared by every domain
    outbox-module.ts
    outbox.entity.ts                   # the Outbox table Entity
    outbox-writer.ts                   # saves an event inside a transaction (called from a Repository)
    outbox-poller.ts                   # publishes Outbox → SQS (@Interval(1000))
    outbox-consumer.ts                 # routes SQS → EventHandlerRegistry (long polling)
    sqs-client-provider.ts             # creates the SQSClient
    event-handler-registry.ts          # eventType → Handler routing
    # there's no per-domain OutboxRelay — it's unified into this single
    # OutboxPoller/OutboxConsumer, and every domain's events are processed
    # asynchronously via SQS (see domain-events.md).
  task-queue/                          # the Task Queue module (shared)
    task-queue-module.ts
    task-queue.ts                      # the interface (abstract class)
    task-queue-outbox.ts               # the Outbox-based implementation (writes to task_outbox)
    task-outbox.entity.ts              # the task_outbox table Entity
    task-outbox-relay.ts               # publishes task_outbox → SQS (Cron)
    task-execution-log.ts              # the TaskExecutionLog interface (abstract class)
    task-execution-log-db.ts           # the DB-based implementation
    task-execution-log.entity.ts       # the task_execution_log table Entity (the idempotency ledger)
    task-execution-log-cleaner.ts      # ledger cleanup (Cron)
    task-consumer.decorator.ts         # the @TaskConsumer decorator (includes the heartbeat option)
    task-consumer-registry.ts          # taskType → Handler routing
    task-queue-consumer.ts             # dispatches SQS → the Task Controller (polling)
  config/
    <concern>.config.ts              # a config factory per concern (database, jwt, etc.)
    validation.config.ts             # environment variable validation (follows the harness's *.config.ts naming rule)
  <domain>/
    domain/                          # the domain layer
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <aggregate>-repository.ts      # the Repository interface (abstract class)
    application/
      adapter/
        <external-domain>-adapter.ts    # the external-domain call interface (abstract class)
      service/
        <concern>-service.ts            # the technical infrastructure interface (abstract class)
      command/
        <domain>-command-service.ts     # the Command Service (write — uses the Repository)
        <verb>-<noun>-command.ts
      query/
        <domain>-query-service.ts       # the Query Service (read — uses the Query interface)
        <domain>-query.ts               # the Query interface (abstract class)
        <verb>-<noun>-query.ts
        <verb>-<noun>-result.ts
      event/
        <domain>-event-handler.ts       # a Domain Event handler — registered in the shared
                                         # outbox/event-handler-registry.ts from the domain
                                         # module's onModuleInit(). There's no per-domain
                                         # outbox-relay.ts — see domain-events.md
    interface/
      <domain>-controller.ts              # the HTTP Controller
      <domain>-task-controller.ts         # the Task Controller (has @TaskConsumer methods)
      dto/
        <verb>-<noun>-request-body.ts     # a request DTO
        <verb>-<noun>-request-param.ts
        <verb>-<noun>-request-querystring.ts
        <verb>-<noun>-response-body.ts    # a response DTO
    infrastructure/
      entity/
        <entity>.entity.ts               # a TypeORM Entity
      <aggregate>-repository-impl.ts    # the Repository implementation
      <domain>-query-impl.ts            # the Query implementation (read-only DB access)
      <external-domain>-adapter-impl.ts # the external-domain Adapter implementation
      <concern>-service-impl.ts         # the technical infrastructure Service implementation
      <concern>-scheduler.ts            # the Scheduler (@Cron → TaskQueue.enqueue)
    <domain>-module.ts
    <domain>-error-message.ts
    <domain>-enum.ts
    <domain>-constant.ts
```

When a Technical Service implementation doesn't fit in a single implementation file and needs several supporting files (a client provider, a dedicated Entity, etc.), group them under `infrastructure/<concern>/` — the same approach as `entity/`. For example, Account's SES email sending (`NotificationService`, the Technical Service example in [domain-service.md](../../../../docs/architecture/domain-service.md)) is laid out as `account/application/service/notification-service.ts` (interface) + `account/infrastructure/notification/{notification-service-impl.ts,ses-client-provider.ts,sent-email.entity.ts}` (the implementation · the SES client provider · a dedicated Entity). Don't extract it into a top-level shared module unless another domain actually shares it (see [shared-modules.md](shared-modules.md)).
