# AI Agent Self-Review Checklist

After finishing work, review the checklist below in order.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

### Verification rules

- When verifying each STEP, always read the relevant file with the Read tool and compare it against the actual code.
- Passing an item without reading the code is prohibited.
- If a violation is found, fix it immediately, then move on to the next STEP.

---

## STEP 1 — File structure and naming

**Related docs**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] Are there any file names that aren't kebab-case?
    → If so, rename to kebab-case
[ ] Are service files in .service.ts format?
    → If so, rename to <domain>-service.ts format
[ ] Are module files in .module.ts format?
    → If so, rename to <domain>-module.ts format
[ ] Are DTO file names verb-first instead of resource-first?
    → Correct example: get-order-request-param.ts, create-order-request-body.ts
[ ] Is an enum declared inline inside another file?
    → If so, extract it into a <domain>-enum.ts file at the module root
[ ] Is a constant declared inline inside another file?
    → If so, extract it into a <domain>-constant.ts file at the module root
[ ] Are enum / constant files located somewhere other than the module root?
    → If so, move them to the same directory as <domain>-module.ts
[ ] Are Param (read) files placed under application/query/?
    → Read-side Params belong in the query/ directory
[ ] Are Param (write, URL-parameter-only) files placed under application/command/?
    → Write-side Params are defined in command/ as <verb>-<noun>-command.ts
[ ] Is the controller file named <domain>-controller.ts?
    → Rename if it's <domain>.controller.ts or another format
[ ] Do Domain layer file names follow the convention?
    → Aggregate Root: <aggregate-root>.ts, Entity: <entity>.ts, Value Object: <value-object>.ts, Domain Event: <domain-event>.ts
[ ] Is the Repository interface file named <aggregate>-repository.ts? (domain/ layer)
[ ] Are Query/Result file names in <verb>-<noun>-query.ts / <verb>-<noun>-result.ts format?
    → The verb (get, find, etc.) should match the Controller method name
[ ] Do Adapter file names follow the convention?
    → Interface: <external-domain>-adapter.ts (application/adapter/)
    → Implementation: <external-domain>-adapter-impl.ts (infrastructure/)
[ ] Do technical infrastructure Service file names follow the convention?
    → Interface: <concern>-service.ts (application/service/)
    → Implementation: <concern>-service-impl.ts (infrastructure/)
[ ] When using @nestjs/cqrs: do Handler file names follow the convention?
    → CommandHandler: <verb>-<noun>-command-handler.ts
    → QueryHandler: <verb>-<noun>-query-handler.ts
    → EventHandler: <domain-event>-handler.ts (application/event/)
[ ] Is the config file named <concern>.config.ts and located in the config/ directory?
[ ] Is the config validation file named validation.config.ts and located in the config/ directory?
[ ] Do class names follow the naming convention?
    → Aggregate Root: domain noun (Order, User)
    → Value Object: domain concept (Money, Address, OrderItem)
    → Domain Event: past tense (OrderPlaced, OrderCancelled)
    → Repository interface: <Aggregate>Repository / implementation: <Aggregate>RepositoryImpl
    → Adapter interface: <ExternalDomain>Adapter / implementation: <ExternalDomain>AdapterImpl
    → Command: <Verb><Noun>Command / Query: <Verb><Noun>Query / Result: <Verb><Noun>Result
    → DTO: <Verb><Noun>Request<Type> / <Verb><Noun>Response<Type>
    → ErrorMessage enum: <Domain>ErrorMessage
    → @nestjs/cqrs Handler: <Verb><Noun>CommandHandler / <Verb><Noun>QueryHandler / <DomainEvent>Handler
```

---

## STEP 2 — Domain layer

**Related docs**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/design-principles.md](./architecture/design-principles.md) · [domain-service.md](../../../docs/architecture/domain-service.md) (root shared) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] Does the domain/ directory contain the Aggregate Root, Entity, Value Object, Domain Event, and Repository interface?
[ ] Are business rules and invariants encapsulated in the Aggregate Root?
    → If business logic exists in the Application Service, move it to the Aggregate
[ ] Are NestJS decorators (@Injectable, @Module, etc.) used in a Domain layer file?
    → If so, remove them. The Domain layer must be framework-independent
[ ] Does a Domain layer file have ORM-related imports (TypeORM, etc.)?
    → If so, remove them. ORM imports are only for the Infrastructure layer
[ ] Is the Repository interface defined as an abstract class?
[ ] Is the Repository interface located in the domain/ layer?
[ ] Are Aggregates referenced only by ID, without direct references between them?
[ ] Is there code outside the Aggregate that directly mutates its internal state?
    → If so, change it to go through the Aggregate Root's methods
[ ] If a Domain Service exists, is it located in the domain/ layer and written without framework decorators?
[ ] Does the Value Object implement an equals() method (attribute-based equality comparison)?
[ ] Is the Aggregate's ID generated as a UUID v4 (hyphens removed, 32-character hex string) at creation?
    → Use generateId() and assign it in the constructor (params.orderId ?? generateId())
```

---

## STEP 3 — Layer architecture

**Related docs**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Does the Controller do anything besides calling the Service + .catch() error conversion?
    → If so, move it to the Service
[ ] Does the Application Service perform business logic directly? (checking state-change conditions, calculations, etc.)
    → If so, move it to a domain method on the Aggregate
[ ] Does the Service throw HttpException / NotFoundException etc.?
    → If so, replace with a plain Error
[ ] Does the Service directly use an ORM client (a TypeORM Repository, etc.)?
    → If so, move it into the Repository implementation
[ ] Does the Repository implementation contain business logic?
    → If so, move it to the Aggregate or the Service
[ ] Is the Service class's member ordering (1) private readonly fields → (2) constructor → (3) public methods → (4) private methods?
[ ] Does the Application layer's directory structure match directory-structure.md? (command/, query/, etc.)
    → If a write use case exists, a command/ directory and a Command object must exist
    → If a read use case exists, a query/ directory and Query/Result objects must exist
[ ] Is the Application Service split into a Command Service and a Query Service?
    → Command Service: injects the Repository and performs write operations
    → Query Service: injects the Query interface (abstract class) and performs read operations
[ ] Does the Query Service use the Repository directly?
    → If so, replace it with a Query interface (abstract class in application/query/, implementation in infrastructure/)
[ ] Is the Query interface defined as an abstract class in application/query/?
[ ] Is the Query implementation located in infrastructure/ and registered in the Module as { provide: Query, useClass: QueryImpl }?
[ ] Does the Interface DTO have extra logic or fields beyond extends?
    → If so, move it to the Application Query/Result/Command
[ ] When using @nestjs/cqrs: does the Controller inject the CommandBus/QueryBus instead of the Service?
[ ] When using @nestjs/cqrs: does the CommandHandler/QueryHandler perform business logic directly?
    → If so, move it to a domain method on the Aggregate (the Handler should only act as a coordinator)
[ ] When using @nestjs/cqrs: is CqrsModule included in the Module's imports?
[ ] When using @nestjs/cqrs: are all CommandHandlers/QueryHandlers/EventHandlers registered in the Module's providers?
[ ] Even when using @nestjs/cqrs, does Domain Event publishing still follow the Outbox + SQS pattern?
    → Do not publish events by using the EventBus directly. Follow the Repository.save() → Outbox → SQS → @HandleEvent handler order
[ ] Is the layer dependency direction correct? (Interface → Application → Domain ← Infrastructure)
    → Fix any code where a lower layer imports from a higher layer
[ ] Are all Controller methods public async, with an explicit Promise<ResponseType> return type?
[ ] Are child Entities inside an Aggregate saved/retrieved together through the Aggregate Root's Repository?
    → Do not create a separate Repository for a child Entity
[ ] Are decorators such as @ApiProperty, class-validator, etc. used on the Application layer (Query/Result/Command)?
    → Allowed. However, prohibited in the Domain layer
[ ] Are events created only inside domain methods within the Aggregate?
    → The Command Service must not create events directly
[ ] In the Repository implementation's save method, if domainEvents exist, are they saved to the outbox together via outboxWriter.saveAll()?
    → The Command Service must not handle the outbox directly. The Repository saves the Aggregate + outbox in the same transaction
[ ] Does the Repository implementation's save method call aggregate.clearEvents() after saving to the outbox?
[ ] Does the Domain EventHandler specify eventType via the @HandleEvent decorator and reside in application/event/?
[ ] Is the EventHandler registered in the domain Module's providers?
    → OutboxWriter/EventHandlerRegistry are provided by the @Global() OutboxModule. Domain EventHandlers are registered in the domain module's providers, and the domain module's onModuleInit() links the eventType to the handler via EventHandlerRegistry.register() — no per-domain OutboxRelay is used (see domain-events.md)
[ ] Are events that need to be announced to external BCs converted into and published as Integration Events?
    → Do not pass the Domain Event object directly to the outside. The Application EventHandler constructs an IntegrationEventV<N> and loads it via OutboxWriter
[ ] Is the Integration Event class defined at application/integration-event/<name>-integration-event.ts, including a version suffix (V1, etc.) and an eventName literal (`<domain>.<verb-past>.v<N>`)?
[ ] When OutboxWriter is referenced from the Application layer, is it limited to the application/event/ EventHandler?
    → It must not be referenced from other Application subdirectories such as application/command/. Only the Repository implementation or an EventHandler may access the outbox
[ ] Is receiving an Integration Event published by an external BC implemented in interface/integration-event/<domain>-integration-event-controller.ts?
    → Receive it via an @HandleIntegrationEvent('<event-name>.v<N>') method and call only the Command Service. No business logic or generateErrorResponse (same as the Task Controller)
[ ] Is the Integration Event Controller registered in the domain Module's providers?
```

---

## STEP 4 — Repository pattern

**Related docs**: [architecture/repository-pattern.md](./architecture/repository-pattern.md) · [architecture/persistence.md](./architecture/persistence.md)

```
[ ] Is the Repository defined at the Aggregate Root level? (not at the Entity/table level)
[ ] Is the Repository interface (abstract class) in the domain/ layer?
[ ] Is the Repository implementation in the infrastructure/ layer?
[ ] Is the Repository implementation file named <aggregate>-repository-impl.ts?
[ ] Do Repository method names follow the find<Noun>s / save<Noun> / delete<Noun> pattern?
[ ] Does the Repository have an update<Noun> method?
    → If so, remove it. Fetch, modify via the Aggregate's domain method, then save via save<Noun>
[ ] Was a separate findOne / findById method created for single-record lookups?
    → If so, remove it. In the Service, use the take: 1 + .then(r => r.<noun>s.pop()) pattern
[ ] Does the Service directly manage the cascade order inside save/delete?
    → If so, move that cascade logic into the Repository implementation
[ ] Does the Repository implementation inject a TypeORM Repository via @InjectRepository?
[ ] Does the Repository implementation convert DB records into domain Aggregate objects?
    → Don't return the DB row as-is; convert it with new Aggregate(row)
[ ] Is the key name of the Repository find method's return type the plural of the domain object name?
    → Correct example: { orders: Order[]; count: number }
    → Incorrect example: { items: Order[]; count: number }, { result: Order[] }
```

---

## STEP 5 — NestJS modules and DI wiring

**Related docs**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/bootstrap.md](./architecture/bootstrap.md)

```
[ ] Is the module organized around a domain (Bounded Context)?
    → Do not split it by layer (a controllers module, a services module)
[ ] Does a single module contain all 4 layers — domain/application/interface/infrastructure?
[ ] Does the Application Service directly inject another domain's Service/Repository?
    → If so, switch to the Adapter pattern: interface in application/adapter/, implementation in infrastructure/
[ ] Is the Adapter interface defined as an abstract class in application/adapter/?
[ ] Is the Adapter implementation located in infrastructure/, and registered in the Module as { provide: Adapter, useClass: AdapterImpl }?
[ ] Does the external domain module export the services it needs to expose, and does the consuming module import it?
[ ] Is there a circular dependency between modules (A → B → A)?
    → If so, re-adjust the Bounded Context boundaries or switch to event-based communication
[ ] Is the Repository registered in the Module's providers as { provide: AbstractClass, useClass: ImplClass }?
[ ] Does the Service constructor inject the Repository by its abstract class type?
[ ] Is TypeOrmModule.forFeature([...entities]) registered in the Module's imports?
[ ] Are all Services and Repositories used registered in the Module's providers?
[ ] When a cross-domain call is needed, is it made through an Adapter?
[ ] Is technical infrastructure such as encryption/decryption, file storage, or an external API client implemented directly in the Application Service?
    → If so, switch to the technical infrastructure Service pattern: interface in application/service/, implementation in infrastructure/
[ ] Is the technical infrastructure Service interface defined as an abstract class in application/service/?
[ ] Is the technical infrastructure Service implementation located in infrastructure/, and registered in the Module as { provide: Service, useClass: ServiceImpl }?
[ ] Is forwardRef() used to resolve a circular dependency?
    → If so, remove it. Re-adjust Bounded Context boundaries or switch to event-based communication
[ ] Does the Adapter interface define only the methods it needs, without exposing the full API of the external domain?
[ ] Does the Adapter's query method follow the same find<Noun>s pattern as the Repository?
    → For single-record lookups, use the take: 1 + .then(r => r.<noun>s.pop()) pattern
[ ] Is a simple utility function (date formatting, string conversion, etc.) split out as a technical infrastructure Service?
    → If so, change it to a plain function. This pattern only applies to technical concerns with real external-system integration or swappable implementation technology
[ ] Does the server directly handle file binaries for upload/download?
    → If so, switch to the Presigned URL pattern. The server only issues the URL; the client communicates directly with storage
[ ] Does the file-owning Entity have fileKey (char 32) and extension (varchar) columns?
    → The DB stores only metadata; the file itself lives in storage
[ ] Is StorageService split using the technical infrastructure Service pattern (abstract class in application/service/, implementation in infrastructure/)?
[ ] Is app.enableShutdownHooks() called in main.ts?
    → Add it if missing. Without this call, the OnApplicationShutdown/BeforeApplicationShutdown hooks won't run
[ ] Does the Infrastructure class managing external connections (DB, Redis, Queue, etc.) implement OnApplicationShutdown?
    → Required when directly managing a custom DataSource, Redis, Bull Queue, etc.
[ ] Is ConfigModule.forRoot() registered in AppModule with isGlobal: true?
[ ] Is per-environment-variable configuration split into concern-specific files (config/<concern>.config.ts)?
[ ] Is required environment variable validation defined in validation.config.ts using class-validator?
[ ] Does validation failure call process.exit(1) to halt app startup?
```

---

## STEP 6 — TypeScript typing

**Related docs**: [conventions.md](./conventions.md)

```
[ ] Are all fields of DTO / Result / Query classes public readonly?
[ ] Does the Command class use the Object.assign constructor pattern?
[ ] Is `any` used anywhere?
    → If so, replace it with a concrete type
[ ] Are nullable fields coming from the DB typed as string | null? (undefined is prohibited)
[ ] Is a domain state value (status, etc.) defined as a literal union type? (instead of string)
[ ] Is the Service method's return type explicit?
[ ] Is the Controller method's return type explicit?
[ ] When saving to the DB, is the time value converted from UTC to KST (UTC+9) before saving?
    → Saving new Date() as-is stores it in UTC
[ ] Is a time value read from the DB returned as-is, without further conversion?
    → Converting an already-KST value again causes a double-conversion bug
[ ] Is an optional parameter declared as ? (T | undefined)?
    → Distinguish DB nullable fields (T | null) from optional parameters (?)
[ ] Is a type alias used for complex types?
    → Correct example: type OrderWithItems = Order & { items: OrderItem[] }
```

---

## STEP 7 — Error handling

**Related docs**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Is the Controller's .catch() block in the form this.logger.error(error) + throw generateErrorResponse(...)?
[ ] Does the error message in generateErrorResponse's second argument reference an ErrorMessage enum value?
    (Using a free-form string literal directly is prohibited)
[ ] Is the generateErrorResponse mapping tuple in the 3-tuple format [ErrorMessage, ExceptionClass, ErrorCode]?
    → The second argument must include a unique ErrorCode enum value for each error case
[ ] Does the error code enum file exist at the module root, named <domain>-error-code.ts?
[ ] Is the error code enum class named <Domain>ErrorCode, with all keys/values in SCREAMING_SNAKE_CASE?
[ ] Does every entry in <Domain>ErrorMessage have a corresponding 1:1 entry in <Domain>ErrorCode?
    → Avoid a message existing without a code, or vice versa
[ ] Does the error response body follow the 4-field format { statusCode, code, message, error }?
    → generateErrorResponse must construct the HttpException in this format
[ ] Are all error messages thrown from the Service defined in the <Domain>ErrorMessage enum?
    → If not, add them to the enum and reference it
[ ] Are error messages thrown from the Aggregate also defined in the <Domain>ErrorMessage enum?
[ ] Does the error message enum file exist in <domain>-error-message.ts format?
[ ] Does the Domain/Service use throw new HttpException / NotFoundException, etc.?
    → If so, replace with throw new Error(ErrorMessage['...'])
```

---

## STEP 8 — REST API endpoints

**Related docs**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/api-response.md](./architecture/api-response.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md)

```
[ ] Is the URL composed of a plural noun resource rather than a verb?
    → Correct example: GET /orders, POST /orders
    → Incorrect example: GET /getOrders, POST /createOrder
[ ] Is the resource name plural?
    → Correct example: /orders, /users
    → Incorrect example: /order, /user
[ ] Does the URL use only lowercase kebab-case?
    → Correct example: /order-items, /payment-methods
    → Incorrect example: /orderItems, /PaymentMethods
[ ] Is the HTTP method used correctly?
    → GET: read, POST: create, PUT: full update, PATCH: partial update, DELETE: delete
[ ] Is a non-CRUD action expressed as a sub-resource path?
    → Correct example: POST /orders/:orderId/cancel
    → Incorrect example: POST /cancelOrder/:orderId
[ ] Is resource nesting within 2 levels?
    → If 3+ levels, split into a top-level resource
[ ] Does the response code match the HTTP method?
    → GET/PUT/PATCH: 200, POST: 201, DELETE: 204
[ ] Does the URL avoid a trailing slash (/) or file extension (.json)?
```

---

## STEP 9 — Swagger documentation

**Related docs**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [conventions.md](./conventions.md)

```
[ ] Does every Controller method have @ApiOperation({ operationId: '...' })?
[ ] Does every Controller method have one of @ApiOkResponse / @ApiCreatedResponse / @ApiNoContentResponse?
[ ] Does @ApiOkResponse on a GET endpoint with a response body specify { type: ResponseBodyClass }?
[ ] Does the POST endpoint have @ApiCreatedResponse()?
[ ] Does a DELETE endpoint with no response body have @HttpCode(204) + @ApiNoContentResponse()?
[ ] Do RequestBody / Param / Querystring DTO fields have class-validator decorators?
[ ] Do all public fields of Query/Result classes have @ApiProperty or @ApiPropertyOptional?
[ ] Does @ApiProperty on a nullable field specify { nullable: true, type: ... }?
[ ] Does @ApiProperty on an array type specify { type: [ItemClass] }?
[ ] Is @ApiProperty written on the Application Query/Result rather than the Interface DTO (Request/Response)?
[ ] Is @ApiPropertyOptional() combined with @IsOptional() on optional Querystring fields?
[ ] Does @ApiProperty specify minimum/maximum/default for numeric fields?
[ ] Is @ApiOperation({ deprecated: true }) shown on endpoints planned for removal?
    → Don't delete immediately; mark deprecated first and allow a migration period
```

---

## STEP 10 — Import organization

**Related docs**: [conventions.md](./conventions.md)

```
[ ] Are relative-path imports (../, ./) used anywhere?
    → If so, replace with absolute paths (@/ alias or src/-based — choose per project settings)
[ ] Are imports organized into 2 groups (external packages → blank line → internal absolute paths)?
[ ] Are internal absolute-path imports sorted alphabetically by path?
[ ] Is there a file using a default export?
    → If so, change to a named export
[ ] When the Application Service imports <Domain>ErrorMessage, is it aliased `as ErrorMessage`?
    (In the Domain layer, importing under the full name is fine)
```

---

## STEP 11 — Module decorators

**Related docs**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/scheduling.md](./architecture/scheduling.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/authentication.md](./architecture/authentication.md)

```
[ ] Does the Controller class have @ApiTags()?
[ ] Is @UseGuards(AuthGuard) + @ApiBearerAuth('token') applied at the class level on Controllers that require authentication?
[ ] Is AuthGuard NOT applied to Controllers that don't require authentication (e.g. AuthController)?
[ ] Does AuthGuard extract the Bearer token from the Authorization header and verify it via AuthService.verify()?
[ ] Is user information assigned to request.user on successful authentication?
[ ] Is the Guard/Interceptor applied at the class level rather than the method level?
[ ] Does the Controller class have private readonly logger = new Logger(XxxController.name)?
[ ] Is Logger NOT used in the Domain layer?
    → The Domain layer is framework-independent. Logging happens in the Application layer
[ ] Do AppModule's imports include ScheduleModule.forRoot() and TaskQueueModule?
    → If missing, @Cron silently doesn't run
[ ] Are Cron jobs (@Cron, @Interval) placed in the Infrastructure layer?
    → Scheduling decorators are prohibited in the Application/Domain layers
[ ] Does the Scheduler (@Cron handler) avoid executing business logic directly, calling only TaskQueue.enqueue?
[ ] Does the Scheduler's @Cron handler explicitly log failures with try-catch + logger.error?
    → @nestjs/schedule silently swallows exceptions from Cron handlers, so failures are unobservable without explicit logging
[ ] Does TaskQueue.enqueue follow the Outbox path — writing a row to task_outbox, with TaskOutboxRelay publishing to SQS?
    → Ensures atomicity between the Command transaction and Task enqueuing (prevents dual-write)
[ ] Is Ad-hoc Task enqueuing called inside the Command's transaction (transactionManager.run)?
[ ] Is the Task Controller placed in the Interface layer (src/<domain>/interface/), injecting the CommandService (+ TaskExecutionLog if needed) and executing only the Command?
    → It's the same kind of input adapter as an HTTP Controller. No conditional branching or business logic
[ ] Does the Task Controller avoid directly injecting DataSource / Repository<Entity> / TaskExecutionLog, etc.?
    → By default, inject only the CommandService. The ledger is delegated to the framework via @TaskConsumer's idempotencyKey option
    → Inject TaskExecutionLog directly only for exceptional cases that require strong atomicity
[ ] Does the Task Controller throw errors as-is?
    → The HTTP Controller's .catch + generateErrorResponse pattern is prohibited. Exceptions are caught by TaskQueueConsumer, which delegates to retry/DLQ
[ ] Is @TaskConsumer('taskType') applied to the Task Controller's method?
[ ] Is the taskType string globally unique? (Duplicate @TaskConsumer registration fails at bootstrap)
[ ] Do all domains share a single Task queue? (Per-domain queue splitting is prohibited)
[ ] Does a FIFO queue + MessageDeduplicationId (date/entity-based) prevent duplicate enqueuing from the same Cron timing?
[ ] Does TaskQueueConsumer avoid deleting the message on failure, letting it be automatically re-received after the visibility timeout?
    → Swallowing the exception with try-catch and calling DeleteMessage would silently lose the failure
[ ] Are both the Task queue and a DLQ configured, with maxReceiveCount (RedrivePolicy) set?
[ ] Is the Command called by the @TaskConsumer method implemented idempotently, so the result is the same under at-least-once delivery?
    → When entity-level idempotency is needed, specify @TaskConsumer's idempotencyKey option (the framework ledger applies it automatically)
[ ] Does a long-running Task (with unpredictable processing time) use @TaskConsumer's heartbeat option?
    → Keep the initial VisibilityTimeout short, and extend only the necessary taskTypes via heartbeat
[ ] Does TaskQueueConsumer await pollPromise in OnApplicationShutdown to wait for in-flight Tasks to complete?
[ ] Is a cleanup Cron configured for the task_outbox / task_execution_log tables?
    → Left unattended, they grow indefinitely
[ ] Is the Task Controller registered in the domain module's providers so it can be resolved via ModuleRef?
    → NestJS's controllers array is for route mapping. The Task Controller is registered in providers
```

---

## STEP 12 — DB / infrastructure patterns

**Related docs**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/container.md](./architecture/container.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] Does the TypeORM Entity extend BaseEntity, including createdAt, updatedAt, deletedAt columns?
[ ] Does deletion use manager.softDelete() rather than manager.delete()?
    → Hard delete (manager.delete) is prohibited
[ ] Are child entities also soft-deleted together?
[ ] Are TypeORM Entity property names camelCase? (snake_case columns are mapped via @Column({ name: '...' }))
[ ] Are write operations spanning multiple Repositories wrapped in TransactionManager.run()?
    → A transaction is required whenever the Command Service calls 2 or more Repositories
[ ] Does a Command that calls only a single Repository avoid an unnecessary TransactionManager.run()?
[ ] Does the Repository implementation use transactionManager.getManager() for write operations?
[ ] Does a multi-step write inside the Repository implementation use transactionManager.getManager()?
[ ] Does DatabaseModule export TransactionManager as @Global()?
[ ] Is the spread pattern used for dynamic where conditions?
[ ] Is the mapping table accessible from both domains' Repository implementations?
[ ] Is the cascade for child entities and the mapping table on save/delete handled inside the Repository implementation?
    → The Service should not manage the cascade order directly
[ ] Is the key name of a find + count (pagination) result the plural of the domain object name?
    → Correct example: { orders: [...], count: 10 }
    → Incorrect example: { result: [...], data: [...] }
[ ] Is the .then() chaining pattern used for single-record lookup and conversion?
    → Correct example: .findOrders({ orderId, take: 1, page: 0 }).then((r) => r.orders.pop())
[ ] Is the QueryBuilder conditional chaining pattern used for dynamic where conditions?
    → Correct example: if (query.status) qb.andWhere('order.status IN (:...status)', { status: query.status })
[ ] Was a migration file generated after modifying the Entity?
    → synchronize: true is prohibited in production
[ ] Is the event handler implemented idempotently?
    → Check whether the state was already processed before processing, or prevent duplicates via a DB unique constraint
[ ] Are sensitive values such as DB passwords, JWT secrets, or external API keys fetched from AWS Secrets Manager in production?
    → Direct injection via environment variables is prohibited. In the config factory, branch on NODE_ENV: use env locally, and SecretsManagerClient or SecretService in production
[ ] Is the SecretService interface defined as an abstract class in application/service/, with the implementation in infrastructure/?
    → This is the technical infrastructure Service pattern. Use a TTL in-memory cache to avoid repeated lookups
[ ] Does a config factory referencing a sensitive key (*_PASSWORD, *_SECRET, *_API_KEY, *_TOKEN) avoid returning the value solely from process.env, without a NODE_ENV branch or SecretService injection?
    → Even a single env-only path risks exposing the secret in plaintext in production
[ ] Is a secret value, or a password/token literal of 8+ characters, free of being hardcoded in source code or commit history?
```

---

## STEP 13 — Test patterns

**Related docs**: [architecture/testing.md](./architecture/testing.md)

```
[ ] Is the Domain layer unit test written in pure TypeScript, without the framework?
    → Test directly with new Aggregate(), without the NestJS Test module
[ ] Does the Application Service test replace the Repository with a mock?
    → Use the jest.Mocked<AbstractClass> pattern
[ ] Does the E2E test verify the use-case flow through real HTTP requests?
[ ] Does the E2E/integration test use an in-memory SQLite DB (or testcontainers)?
    → Do not connect directly to the production DB
[ ] Is there a test for Aggregate invariant violations? (invalid input → exception raised)
[ ] Is there a test verifying whether a Domain Event was published?
[ ] Is the Domain unit test placed as a .spec.ts file in the same directory as the source file?
[ ] Is the E2E test placed as a .e2e-spec.ts file in the test/ directory?
[ ] Does test naming follow the {domain-action}_when_{condition}_then_{expected-result} pattern?
```

---

## STEP 14 — Final overall consistency check

**Related docs**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] Are all errors that could occur in the Controller's .catch() mapped in generateErrorResponse's second argument?
    → If any error is missing, add the [ErrorMessage['...'], ExceptionClass] pair
[ ] Is a newly added file registered in the Module's providers / controllers?
[ ] If a new Query / Command / Result object was created, does the Interface DTO wrap it via extends?
[ ] Does the code you worked on have no leftover TODOs, console.log, or temporary comments?
[ ] Is the Ubiquitous Language consistently reflected in the code (class names, method names, variable names)?
[ ] Does the comment style use only // inline comments? (JSDoc is prohibited)
[ ] Is logger output structured? (snake_case field names when integrating with external monitoring)
[ ] Does the commit message follow the Conventional Commits format (feat/fix/refactor + scope)?
[ ] Is the commit message's scope the service domain name (order, user, payment, etc.)?
    → For changes spanning multiple domains, omit the scope or use a higher-level concept
[ ] Is the commit message description written in the descriptive form with no trailing period?
    → Correct example: feat(order): add order cancellation
    → Incorrect example: feat(order): Add the order cancellation feature.
[ ] Does the commit message body explain "why" the change was made? (not "what")
[ ] If there's a BREAKING CHANGE, is it indicated via a footer or a ! after the type?
[ ] Does the branch name follow the Conventional Branch format (<type>/<scope>-<description>)?
[ ] Is the branch name all kebab-case, branched from main?
[ ] Are changes applied via PR rather than committing/pushing directly to the main branch?
[ ] Is the PR title identical in format to Conventional Commits?
[ ] Does the PR body follow the Summary (1-3 lines of changes) + Test plan (test checklist) format?
[ ] Is the merge strategy Squash and merge?
[ ] Does test naming follow the {domain-action}_when_{condition}_then_{expected-result} pattern?
[ ] Are long Service methods logically divided with section comments (// fetch order info from DB, etc.)?
```

---

## STEP 15 — Design deliverable format (for design-phase work)

**Related docs**: [development-process.md](../../../docs/development-process.md) (root shared) · [reference.md](./reference.md)

> Applies only when design-phase (RA, SD, DM, TD) deliverables were produced.

```
[ ] RA deliverable: do functional requirements include an FR-### number, description, acceptance criteria, and priority (MoSCoW)?
[ ] RA deliverable: does the use case include a UC-### number, Actor, preconditions, main flow (Happy Path), exception flows, and postconditions?
[ ] RA deliverable: does the constraints summary table include tech stack, external systems, schedule, regulations, and traffic items?
[ ] SD deliverable: does the subdomain classification table include type (Core/Supporting/Generic) and implementation strategy?
[ ] SD deliverable: does the Bounded Context definition include responsibilities, core concepts, and parent subdomain?
[ ] SD deliverable: does the Context Map include the relationship type (Partnership/Shared Kernel/Customer-Supplier/Conformist/ACL/OHS·PL) and the reason for choosing it?
[ ] DM deliverable: does the event storming result mapping table include Actor/Command/Aggregate/Domain Event/Policy/External System columns?
[ ] DM deliverable: does the Ubiquitous Language glossary include Term (English)/Term (Korean)/Definition/Context/Notes columns?
[ ] DM deliverable: when the same word has different meanings across different Contexts, is that noted in the glossary?
[ ] DM deliverable: does the per-Aggregate domain model structure include Root/Entity list/VO list/relationships?
[ ] DM deliverable: does the Domain Event detail list include event name/trigger condition/included data/follow-up processing (Policy) columns?
[ ] DM deliverable: do business rules/invariants include an INV-### number and how violations are handled?
[ ] TD deliverable: does the file structure tree include all 4 layers — domain/application/infrastructure/interface?
[ ] TD deliverable: does the Module DI configuration specify the provide/useClass mapping?
[ ] TD deliverable: does the Aggregate design doc include Root/internal Entities/internal VOs/external references (ID)/creation rules/invariants?
[ ] TD deliverable: does the Repository interface definition include find<Noun>s/save<Noun>/delete<Noun> methods?
[ ] TD deliverable: does the Application Service definition include use-case mapping/processing flow/transaction scope/failure handling?
[ ] TD deliverable: does the Event flow diagram include sync/async processing and compensating transactions?
[ ] IM deliverable: is work proceeding via Vertical Slicing (use-case-unit implementation)?
    → Implement all layers at once per use case (vertical), not layer-by-layer (horizontal)
[ ] IM deliverable: is the slice plan organized in slice number/use case/included files/priority format?
```

---

## STEP 16 — When modifying the guide itself

**Related docs**: [development-process.md](../../../docs/development-process.md) (root shared) · [conventions.md](./conventions.md)

> Applies only when modifying the guide itself, rather than doing code work.

```
[ ] Is the newly added or modified description written in English?
[ ] Does the new rule include both a correct example (// correct approach) and an incorrect example (// incorrect approach)?
[ ] Does the example you wrote avoid violating this guide's other rules (file naming, imports, typing, Swagger, etc.)?
    → If there's a violation, fix the example first before finalizing the rule
[ ] When changing the guide, is a PR created from a new branch rather than the main branch?
```

---

## How to use this checklist

After finishing work, the AI Agent performs self-review in the following order:

1. Check **STEPs 1-14 in order**.
2. When a violation is found, **fix the file immediately** and check it off.
3. After fixing, verify that **related files (Module, import references, etc.) are unaffected**.
4. If the work was design-phase work, also check **STEP 15**.
5. If the work was modifying the guide itself, also check **STEP 16**.
6. Wrap up the work once all checks are complete.

> This checklist summarizes the guide's rules.
> If an item's intent is unclear, refer to the corresponding document:
> - STEP 1 File structure and naming → [conventions.md](conventions.md) sections 1-3
> - STEP 2 Domain layer → [layer-architecture.md](architecture/layer-architecture.md), [domain-service.md](../../../docs/architecture/domain-service.md) (root shared), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 Layer architecture / events → [layer-architecture.md](architecture/layer-architecture.md), [domain-events.md](architecture/domain-events.md), [cqrs-pattern.md](architecture/cqrs-pattern.md) / [conventions.md](conventions.md) section 6
> - STEP 4 Repository pattern → [repository-pattern.md](architecture/repository-pattern.md)
> - STEP 5 NestJS DI → [repository-pattern.md](architecture/repository-pattern.md), [module-pattern.md](architecture/module-pattern.md)
> - STEP 6 TypeScript typing → [conventions.md](conventions.md) section 4
> - STEP 7 Error handling → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API endpoints → [conventions.md](conventions.md) section 5
> - STEP 9 Swagger → [conventions.md](conventions.md) section 8
> - STEP 10 Imports → [conventions.md](conventions.md) section 7
> - STEP 11 Module decorators → [module-pattern.md](architecture/module-pattern.md)
> - STEP 12 DB/infrastructure → [repository-pattern.md](architecture/repository-pattern.md), [domain-events.md](architecture/domain-events.md), [persistence.md](architecture/persistence.md)
> - STEP 13 Test patterns → [conventions.md](conventions.md) section 13
> - STEP 14 Overall consistency → refer to the full document
> - STEP 15 Design deliverable format → [development-process.md](../../../docs/development-process.md) (root shared) Agents 1-5
> - STEP 16 Guide modification → this document's [STEP 16](#step-16--when-modifying-the-guide-itself)
