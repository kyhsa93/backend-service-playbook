# Directory Structure

```
src/
  common/                              # shared utilities (see shared-modules.md for the full listing)
    common-module.ts                   # @Global module exporting SecretService
    application/service/secret-service.ts
    infrastructure/                    # secret-service-impl.ts, shutdown-state.ts
    interface/                         # health-controller.ts, metrics-controller.ts, dto/error-response-body.ts
    correlation-id-store.ts            # the AsyncLocalStorage-based Correlation ID store
    correlation-id.middleware.ts
    user-context-store.ts              # the authenticated-user AsyncLocalStorage store
    user-context.interceptor.ts
    generate-error-response.ts
    generate-id.ts
    http-exception.filter.ts           # the global exception filter
    logging.interceptor.ts
    metrics-registry.ts
    metrics.interceptor.ts
  database/                            # shared database code
    base.entity.ts                     # BaseEntity — shared createdAt/updatedAt/deletedAt columns
    data-source.ts                     # the TypeORM DataSource config — shared with the CLI migrations
    transaction-manager.ts             # the transaction manager (AsyncLocalStorage-based)
    migrations/                        # <timestamp>-<change>.ts migration files
  outbox/                              # shared Outbox code (the @Global OutboxModule) — the single path shared by every domain
    outbox-module.ts
    outbox.entity.ts                   # the Outbox table Entity
    outbox-writer.ts                   # saves an event inside a transaction (called from a Repository)
    outbox-poller.ts                   # publishes Outbox → SQS (@Interval(1000))
    outbox-consumer.ts                 # routes SQS → EventHandlerRegistry (long polling)
    sqs-client-provider.ts             # creates the SQSClient (the shared SQS_CLIENT provider)
    event-handler-registry.ts          # eventType → Handler routing
    trace-context.ts
    # there's no per-domain OutboxRelay — it's unified into this single
    # OutboxPoller/OutboxConsumer, and every domain's events are processed
    # asynchronously via SQS (see domain-events.md).
  task-queue/                          # the Task Queue module (@Global, shared)
    task-queue-module.ts
    task-queue.ts                      # the interface (abstract class)
    task-queue-outbox.ts               # the Outbox-based implementation (writes to task_outbox)
    task-outbox.entity.ts              # the task_outbox table Entity
    task-outbox-relay.ts               # publishes task_outbox → SQS (@Interval(3000))
    task-consumer.decorator.ts         # the @TaskConsumer decorator
    task-consumer-registry.ts          # taskType → Handler routing
    task-queue-consumer.ts             # dispatches SQS → the Task Controller (polling)
  auth/                                # the shared authentication module (see authentication.md, shared-modules.md)
  config/
    <concern>.config.ts              # a config factory per concern (database, jwt, etc.)
    validation.config.ts             # environment variable validation (follows the harness's *.config.ts naming rule)
  <domain>/
    domain/                          # the domain layer
      <aggregate-root>.ts
      <entity>.ts
      <value-object>.ts
      <domain-event>.ts
      <domain>-service.ts            # a Domain Service (only when domain judgment spans Aggregates)
      <aggregate>-repository.ts      # the Repository interface (abstract class)
    application/
      adapter/
        <external-domain>-adapter.ts    # the external-domain call interface (abstract class)
      service/
        <concern>-service.ts            # the technical infrastructure interface (abstract class)
      command/
        <verb>-<noun>-command-handler.ts  # a @CommandHandler (write — uses the Repository)
        <verb>-<noun>-command.ts
      query/
        <verb>-<noun>-query-handler.ts  # a @QueryHandler (read — uses the Query interface)
        <domain>-query.ts               # the Query interface (abstract class)
        <verb>-<noun>-query.ts
        <noun>-result.ts
      event/
        <event>-handler.ts              # a Domain Event handler — registered in the shared
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
    <domain>-error-code.ts
    <domain>-enum.ts
    <domain>-constant.ts
```

When a Technical Service implementation doesn't fit in a single implementation file and needs several supporting files (a client provider, a dedicated Entity, etc.), group them under `infrastructure/<concern>/` — the same approach as `entity/`. For example, Account's SES email sending (`NotificationService`, the Technical Service example in [domain-service.md](../../../../docs/architecture/domain-service.md)) is laid out as `account/application/service/notification-service.ts` (interface) + `account/infrastructure/notification/{notification-service-impl.ts,ses-client-provider.ts,sent-email.entity.ts}` (the implementation · the SES client provider · a dedicated Entity). Don't extract it into a top-level shared module unless another domain actually shares it (see [shared-modules.md](shared-modules.md)).
