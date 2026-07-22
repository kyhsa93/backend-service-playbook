# AI Agent Self-Review Checklist — Kotlin Spring Boot

After finishing a task, review the checklist below in order.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

### Verification rules

- When verifying each STEP, always read the relevant file with the Read tool and compare it against the actual code.
- Passing an item without reading the code is forbidden.
- If a violation is found, fix it immediately, then move to the next STEP.
- Don't pass an item just because the existing code in `examples/` differs from it — `examples/` hasn't yet reflected every "known gap" the docs specify, and what this checklist requires is the correct pattern each `architecture/*.md` defines.

---

## STEP 1 — File structure and naming

**Related documents**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] Is there a file whose name isn't PascalCase (filename = the top-level public class name)?
    → If so, rename it to PascalCase.kt (not kebab-case — the Kotlin/Java convention)
[ ] Does any single file have 2 or more top-level public classes?
    → Split into 1 class per file
[ ] Is an enum class declared inline inside another file?
    → Split it out into a <Concept>.kt file under domain/
[ ] Are domain constants declared inline inside another file?
    → Split them into a <Domain>Constants.kt under domain/, or a top-level const val
[ ] Is the Repository interface's filename in the form <Aggregate>Repository.kt? (the domain/ layer)
[ ] Is the Repository implementation's filename in the form <Aggregate>RepositoryImpl.kt? (the infrastructure/persistence/ layer)
[ ] Do Domain-layer filenames follow the rules?
    → Aggregate Root: <AggregateRoot>.kt, Value Object: <ValueObject>.kt, Domain Event: <PascalCase past tense>Event.kt
[ ] Is the exception hierarchy's filename in the form <Domain>Exception.kt (a sealed class + its subclasses in one file)?
[ ] Is the error code's filename in the form <Domain>ErrorCode.kt?
[ ] Is the Command Service/Query Service filename in the form <Verb><Noun>Service.kt? (one class per use case)
[ ] Is the Command/Result filename in the form <Verb><Noun>Command.kt / <Verb><Noun>Result.kt?
[ ] Do Adapter filenames follow the rules?
    → interface: <ExternalDomain>Adapter.kt (application/adapter/)
    → implementation: <ExternalDomain>AdapterImpl.kt (infrastructure/)
[ ] Do technical-infrastructure Service filenames follow the rules?
    → interface: <Concern>Service.kt (application/service/)
    → implementation: <Concern>ServiceImpl.kt (infrastructure/)
[ ] Is the Controller filename in the form <Domain>Controller.kt, located under interfaces/rest/? (not singular interface/ — this repository uses plural interfaces/)
[ ] Is the @ConfigurationProperties data class filename in the form <Concern>Properties.kt, located under config/?
[ ] Do class names follow the naming rules? (conventions.md section 2 — Aggregate/VO/Event/Repository/Service/Command/Result/Adapter/ErrorCode naming)
[ ] Does it pass the harness's file-naming check (^[A-Z][A-Za-z0-9]*$)? (verify via ./harness/harness.sh)
```

---

## STEP 2 — The Domain layer

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [architecture/domain-service.md](../../../docs/architecture/domain-service.md) (root shared doc) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] Does the domain/ directory contain the Aggregate Root, Value Objects, Domain Events, and the Repository interface?
[ ] Are business rules and invariants encapsulated in the Aggregate Root?
    → If the Application Service has a state-change condition check or calculation logic, move it into an Aggregate domain method
[ ] Is the Aggregate/Entity constructor a protected constructor(), with the only public creation path being a single companion object factory function (create())?
    → A violation if external code can create an empty instance with Order()
[ ] Is every Aggregate/Entity property private set?
    → If a var lacks private set, check whether it can be assigned directly from outside, and fix it
[ ] Is @Service/@Component/@Repository/@Controller/@RestController used in a Domain-layer file?
    → If so, remove it. The Domain layer has no Spring dependency
[ ] Does a Domain-layer file import jakarta.persistence (@Entity/@Embeddable/@Column, etc)?
    → If so, remove it. JPA mapping is split out into infrastructure/persistence's JpaEntity/Embeddable + Mapper (see directory-structure.md). Enforced by the harness's domain-purity check
[ ] Is the Repository interface defined as a Kotlin interface? (not an abstract class — in Spring, the interface itself is the DI token)
[ ] Is the Repository interface located in the domain/ layer?
[ ] Does it use only ID (String) references between Aggregates, with no direct references?
[ ] Is there code that changes internal state directly from outside the Aggregate? (this should be blocked at compile time by private set)
[ ] If there is a Domain Service, is it located in the domain/ layer and written with no Spring annotations?
    → Does it hold no state, and never call the Repository directly? (see the "anti-pattern" section of domain-service.md)
[ ] Is the Value Object defined as a data class, with equals()/hashCode() auto-generated?
[ ] Are a Value Object's/Aggregate's invariants validated immediately inside an init { } block or companion object.create()?
[ ] Is the ID generated via generateId() (UUID v4, hyphens removed, a 32-character hex string) when the Aggregate is created?
    → A violation if UUID.randomUUID().toString() is used as-is with hyphens (see the "known gaps" in aggregate-id.md — don't repeat this in new code)
[ ] Is the JPA surrogate key (id: Long?, @GeneratedValue) separated from the domain identifier (accountId/orderId: String)?
    → A Long-typed id must never be exposed externally via the Controller/Command/Result, etc
[ ] Does a child Entity (Transaction, etc) also follow the same protected constructor() + companion object.create() + generateId() pattern as the Aggregate Root?
[ ] Is the child Entity saved/queried only through the Aggregate Root's Repository? (no separate Repository)
```

---

## STEP 3 — Layer architecture / CQRS / Domain Events

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Does the Controller perform business logic beyond calling a Service?
    → If so, move it into a Service or Aggregate
[ ] Does the Application Service (Command/Query) perform business logic directly? (a state-change condition check, an amount calculation, etc)
    → If so, move it into an Aggregate domain method
[ ] Is @Service attached to the Command Service? (there should be no @Transactional — the transaction boundary has been moved to Repository.save(), see STEP 8 below)
[ ] Is @Service + @Transactional(readOnly = true) attached to the Query Service?
[ ] Does the Command Service use only the Repository, and the Query Service (if split) only the Query interface?
    → If the Query Service directly uses the Repository, judge whether it's time for a read-only projection (see the "known differences" in cqrs-pattern.md — if query volume is low and the read model is nearly identical to the Aggregate, this is allowed as a practical simplification)
[ ] If a Query interface was split out, is it an interface under application/query/, with the implementation under infrastructure/?
[ ] Is EntityManager/JpaRepository injected into the Repository implementation's constructor, with Domain/Application knowing nothing about it at all?
[ ] Is the layer dependency direction correct? (interfaces → application → domain ← infrastructure)
    → Fix any code where a lower layer imports a higher layer
[ ] Is a child Entity inside an Aggregate saved/queried together via the Aggregate Root's Repository?
    → Never create a separate Repository for a child Entity
[ ] Is a Domain Event only ever collected inside an Aggregate's internal domain methods? (the domainEvents list + pullDomainEvents())
    → The Command Service never creates an event object directly
[ ] Is Domain Event publishing NOT the synchronous in-process ApplicationEventPublisher.publishEvent() approach?
    → A violation if it is. Save it together in the Outbox table inside Repository.save<Noun>(), and have the Command Service return immediately after saving — draining the Outbox is handled independently by a separate component (see domain-events.md)
[ ] Does the Repository implementation's save<Noun>() method use @Transactional to bundle saving the Aggregate + saving the Outbox into one transaction?
[ ] Does the Command Service never call an Outbox-draining component (OutboxPoller/OutboxConsumer) directly? (a @Scheduled poll (OutboxPoller) → SQS publish → OutboxConsumer independently drains it — see the harness's outbox-no-sync-drain, and domain-events.md)
[ ] If there are multiple event types, are they grouped under a sealed interface so the compiler checks when-branch exhaustiveness?
[ ] Is the Event Handler (`*EventHandler`, processing an event the outbox drained) located in the application/event/ package?
    → the target of the harness's event-placement check
[ ] Is the event handler implemented idempotently? (assuming at-least-once delivery — skip an already-processed eventId)
[ ] When a cross-domain call is needed, is it made through an Adapter interface (application/adapter/, a Kotlin interface)?
    → If another BC's Service/Repository is injected directly, switch it to an Adapter
[ ] Is the Adapter read-only? (it never calls an external BC's state-changing method — writes are switched to an Integration Event)
```

---

## STEP 4 — The Repository pattern

**Related documents**: [architecture/repository-pattern.md](./architecture/repository-pattern.md) · [architecture/persistence.md](./architecture/persistence.md)

```
[ ] Is the Repository defined per Aggregate Root? (not per Entity/table)
[ ] Is the Repository interface in domain/, with the implementation in infrastructure/persistence/?
[ ] Are Repository method names unified into a single pattern of find<Noun>s / save<Noun> / delete<Noun>?
    → If dedicated combination methods like findByXxxAndYyy keep growing, merge them into a single find<Noun>s(query: <Noun>FindQuery)
[ ] Was a dedicated findOne/findById-style method created for a single-item lookup?
    → If so, remove it. Use .firstOrNull() on the result of find<Noun>s(take = 1)
[ ] Does find<Noun>s's return type return the list and the count together? (Pair<List<T>, Long> or a dedicated data class)
    → Check whether the list and count were left as separate methods (findAll/countAll)
[ ] Does the Repository have an update<Noun> method?
    → If so, remove it. Query, then change state via an Aggregate domain method, then save via save<Noun>
[ ] Is delete<Noun>() implemented as a soft delete (updating deletedAt)? (hard delete is forbidden)
[ ] If a deletedAt column exists, is a deletedAt IS NULL condition applied by default to every query?
[ ] Does the Repository implementation convert to a domain Aggregate rather than returning the DB record (Entity) as-is?
    → In this repository the JPA Entity often equals the Aggregate, so a separate conversion often isn't needed — but a Query result (a Result data class) is always converted separately
[ ] Are dynamic filter conditions implemented so a condition is only added when a value is present (a conditional JPQL append, or conditional QueryDSL/Criteria chaining)?
```

---

## STEP 5 — Spring DI / component scanning

**Related documents**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/bootstrap.md](./architecture/bootstrap.md)

```
[ ] Is @Service only inside application/{command,query}/? (the harness's service-annotation check)
[ ] Is @Repository only inside infrastructure/? (the harness's repository-annotation check)
[ ] Is @RestController only inside interfaces/? (the harness's controller-placement check)
[ ] Is the domain (Bounded Context) organized per package? (never split the top-level package by layer)
[ ] Does a single domain package contain all 4 layers — domain/application/infrastructure/interfaces? (the harness's package-structure check)
[ ] Do both the command/ and query/ subpackages exist under application/? (the harness's package-structure check)
[ ] Is constructor injection used? (the primary constructor's parameters are the DI target, no @Autowired needed)
    → If field injection (@Autowired lateinit var) is used, switch it to constructor injection
[ ] Is another domain's Service/Repository injected directly into an Application Service constructor?
    → If so, switch to the Adapter pattern (an application/adapter/ interface + an infrastructure/ implementation)
[ ] Is there a circular dependency (A → B → A) causing the application to fail at startup?
    → Don't temporarily work around it with @Lazy — resolve it at the root via making the Adapter one-way, extracting shared logic, or reconsidering the BC boundary
[ ] Is a third-party client needing a @Bean factory (SesClient, S3Presigner, etc) defined in a @Configuration class?
[ ] Is code that multiple BCs need to share (ID generation, error handlers, Outbox, authentication) placed in the common/·config/·auth/·outbox/ packages shared-modules.md proposes?
    → Was a BC-owned Technical Service (notification, etc) hastily promoted to a shared package?
```

---

## STEP 6 — Kotlin typing / null-safety

**Related documents**: [conventions.md](./conventions.md) section 4

```
[ ] Is the Command/Result/Query class defined as a data class? (no Object.assign pattern needed — the primary constructor replaces it)
[ ] Is Any/Any? used anywhere in a domain/Application public signature?
    → If so, replace it with a concrete type or a sealed interface
[ ] Is "not found"/an optional value expressed as T? (nullable)? (Optional<T> wrapping is forbidden)
[ ] Is there code that force-unwraps (!!) a nullable value without ?:
    → If so, remove it. Handle it safely with the ?: Elvis operator or a smart cast
[ ] Is a domain status value defined as an enum class? (a list of String constants is forbidden)
[ ] Is the Service method's return type explicit? (never leave a public API's type to inference alone)
[ ] Is a wildcard import used anywhere?
    → If so, replace it with individual imports
[ ] Is suspend fun used anywhere?
    → If so, remove it. This repository has decided not to adopt coroutines (see scheduling.md)
```

---

## STEP 7 — Error handling

**Related documents**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Is the domain exception hierarchy defined as sealed class <Domain>Exception : RuntimeException(message)?
[ ] Is the sealed class exception hierarchy located in the domain/ layer? (the harness's sealed-exception check)
[ ] Does each subclass exception map 1:1 to a <Domain>ErrorCode enum value?
    → If there's an exception with only a message and no code, add an ErrorCode
[ ] Are all <Domain>ErrorCode values SCREAMING_SNAKE_CASE?
[ ] Does the error response body follow the 4-field format { statusCode, code, message, error }?
    → If there's an ErrorResponse with only a message field, expand it to 4 fields
[ ] Is error conversion unified via @RestControllerAdvice (the global handler, common/GlobalExceptionHandler.kt)?
    → If @ExceptionHandler is scattered individually across each Controller, consolidate it into the global handler
[ ] Is MethodArgumentNotValidException (a Bean Validation failure) handled to match the 4-field format with code: "VALIDATION_FAILED"?
[ ] Is there a handler that catches an unclassified exception (Exception::class) last and catches-all as 500?
    → If not, there's a risk of Spring's default HTML error page being exposed to an API client
[ ] Does Domain/Application throw an HTTP exception directly (HttpException/ResponseStatusException, etc)?
    → If so, replace it with a sealed class exception. Converting to an HTTP status code is the Interface layer's (@RestControllerAdvice's) responsibility
```

---

## STEP 8 — REST API endpoints

**Related documents**: [conventions.md](./conventions.md) section 5 · [architecture/api-response.md](./architecture/api-response.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md)

```
[ ] Is the URL composed of a plural noun resource rather than a verb? (GET /orders, POST /orders)
[ ] Does the URL use only lowercase kebab-case? (/order-items — don't confuse this with the filename's PascalCase)
[ ] Is the HTTP method used correctly? (GET query, POST create, PUT full update, PATCH partial update, DELETE delete)
[ ] Does the response code match the HTTP method? (GET/PUT/PATCH 200, POST 201, DELETE 204)
[ ] Is a non-CRUD action expressed as a sub-resource path? (POST /orders/{orderId}/cancel)
[ ] Is nesting kept to 2 levels or fewer?
[ ] Is the list-query response's key name the plural of the domain object name? ({ orders: [...], count: N })
[ ] Is pagination expressed via @RequestParam(defaultValue = "0") page / @RequestParam(defaultValue = "20") take?
[ ] Is a Controller that requires authentication protected by the Spring Security Filter Chain (anyRequest, authenticated in authorizeHttpRequests)?
    → Does it follow SecurityConfig's path-pattern whitelist approach, rather than a method/class-level annotation?
[ ] Does the Controller pull only the authenticated user ID out of SecurityContext/Authentication to include in the Command?
    → A violation if a client-sent header (X-User-Id, etc) is trusted as-is without verification
[ ] Does the JWT payload hold only the minimum info (subject=userId)? (email/role, etc are never cached in the token)
[ ] Is Rate Limiting applied Filter-based (OncePerRequestFilter), ahead of authentication/validation?
```

---

## STEP 9 — API documentation (springdoc-openapi)

**Related documents**: [conventions.md](./conventions.md) section 8 · [architecture/bootstrap.md](./architecture/bootstrap.md)

```
[ ] Does every Controller method have @Operation with a non-empty summary AND description?
    → an operationId alone (or a bare route with no metadata) is not sufficient
[ ] Is every non-2xx status the method can actually return documented with @ApiResponse/@ApiResponses(responseCode, description)?
    → cross-check against the method's own exception-mapping (GlobalExceptionHandler) — an undocumented 404/409/etc is a gap, not a style nit. Only documenting the success response is the most common way this rots
[ ] Does the Request DTO's (data class's) Bean Validation annotation have the @field: use-site target attached?
    → Omitting it makes validation silently not work with no compile error (the most common mistake)
[ ] Does every Request/Response DTO field have @Schema(description = "...")?
[ ] Is @Operation(deprecated = true) marked on an endpoint scheduled for deprecation?
[ ] Is the springdoc-openapi dependency added to build.gradle.kts?
[ ] Does `harness/README.md`'s openapi-operation-documented rule pass? (bash harness.sh <projectRoot>)
```

---

## STEP 10 — Import organization

**Related documents**: [conventions.md](./conventions.md) section 7

```
[ ] Is a wildcard import (import x.y.*) used anywhere?
    → If so, replace it with individual imports
[ ] Is there an unused import left behind?
    → Clean it up with IntelliJ's "Optimize Imports" or ktlint
[ ] Is an as alias used unnecessarily when there's no name conflict?
```

---

## STEP 11 — Stereotype annotations / cross-cutting concerns

**Related documents**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md) · [architecture/authentication.md](./architecture/authentication.md) · [architecture/scheduling.md](./architecture/scheduling.md) · [architecture/domain-events.md](./architecture/domain-events.md)

```
[ ] Is the Correlation ID Filter (OncePerRequestFilter, @Order(Int.MIN_VALUE)) registered at the very top of the filter chain?
[ ] Is the Correlation ID put via MDC.put() cleaned up with MDC.remove() in a finally block?
    → Prevents context contamination on thread-pool reuse
[ ] Is the authentication Filter (JwtAuthenticationFilter) positioned after Correlation ID, before the controller?
[ ] Is request logging implemented as a HandlerInterceptor? (not a Filter — since Handler info is needed)
[ ] Does the Domain layer avoid importing a Filter/Interceptor/Logger?
[ ] Is a @Scheduled job located in infrastructure/ or a shared outbox/·scheduling/ package?
    → Using a scheduling annotation in the Application/Domain layer is forbidden
[ ] Does the Scheduler (a @Scheduled method) only enqueue, never directly executing business logic?
[ ] Is an exception from a @Scheduled method explicitly logged via runCatching { }.onFailure { logger.error(...) }?
    → Spring just logs and swallows the exception, so without explicit logging the failure goes unnoticed
[ ] Is duplicate writes at the same Cron timing across multiple instances prevented with a date/entity-based deduplicationId?
[ ] Does writing to the Task Outbox happen together inside the Command's transaction? (atomicity between the DB change and writing the Task, avoiding dual-write)
[ ] Is setWaitForTasksToCompleteOnShutdown(true) set on the ThreadPoolTaskScheduler? (for graceful-shutdown support)
```

---

## STEP 12 — DB / infrastructure patterns

**Related documents**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/container.md](./architecture/container.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] Does the Command Service method have no @Transactional? (the transaction boundary should be on Repository.save()), and does the Query Service method have @Transactional(readOnly = true)?
[ ] Does Repository.save() have @Transactional, with a write spanning multiple Repositories executed inside a single @Transactional method?
[ ] Are the Entity's common columns (createdAt/updatedAt/deletedAt) extracted into a @MappedSuperclass BaseEntity? (from the point there are more than 2 Entities)
[ ] Is soft delete actually wired up? (not a dead column that has only a deletedAt column with no delete<Noun>() execution path)
[ ] Is the production profile's ddl-auto set to validate, not update/create-drop?
[ ] Have Flyway migration files been added to match Entity changes? (db/migration/V<N>__*.sql)
[ ] Is per-concern configuration split into @ConfigurationProperties + data class? (scattered use of @Value string keys is forbidden)
[ ] Is fail-fast (startup aborts on binding failure) naturally guaranteed via required configuration fields with no default value?
    → Use @Validated + @field:NotBlank as needed to also block an empty string
[ ] Are configuration values (DatabaseProperties, etc) injected only in the Infrastructure layer? (never inject them directly into a Domain/Application constructor)
[ ] Is a sensitive value like a DB password or JWT secret never hardcoded in code/commits?
[ ] Is a sensitive value looked up via SecretService (the Technical Service pattern, including a TTL cache) in production?
[ ] Does every infrastructure service in the local docker-compose have a real-API-based healthcheck configured?
[ ] Is the Dockerfile structured as multi-stage (a Gradle build stage + a JRE runtime stage)?
[ ] Is the Dockerfile's CMD in exec form (["java", "-jar", "app.jar"])? (shell form is forbidden — prevents delayed SIGTERM delivery)
[ ] Is server.shutdown: graceful + spring.lifecycle.timeout-per-shutdown-phase configured?
[ ] Are the Actuator liveness/readiness probes enabled? (management.health.livenessstate/readinessstate)
[ ] Does the structured log use snake_case field names? (for monitoring integration)
[ ] Is the Correlation ID automatically propagated throughout the logs via MDC?
```

---

## STEP 13 — Test patterns

**Related documents**: [conventions.md](./conventions.md) section 13 · [architecture/testing.md](./architecture/testing.md)

```
[ ] Is the Domain-layer unit test written in plain Kotlin with no Spring? (calling Aggregate.create() directly, with no @SpringBootTest)
[ ] Does the Application Service test replace the Repository with MockK (mockk<AccountRepository>())?
[ ] Is the E2E test written with Testcontainers (a real Postgres, LocalStack when needed)? (an in-memory substitute DB like H2 is forbidden)
[ ] Is there a test for an Aggregate-invariant violation? (invalid input → a sealed class exception is raised)
[ ] Is there a test verifying whether a Domain Event was collected? (checking the pullDomainEvents() result)
[ ] Are test files placed in the same package structure under src/test/kotlin? (a separate source set, not next to the source)
[ ] Is the test function name a natural-language sentence wrapped in backticks? (in the form `creating an account starts with a 0 balance`())
[ ] Is MockK's relaxed mock/slot capture, etc used appropriately so that every {} returns Unit isn't repeated for every method with no return value?
```

---

## STEP 14 — Final overall consistency check

**Related documents**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] Is every exception handled in @RestControllerAdvice actually covered by a subtype of the sealed class hierarchy?
    → If a new exception was added, confirm it's reflected in the handler's when/mapping
[ ] Does a newly added class have the correct stereotype annotation (@Service/@Repository/@Component/@RestController) attached, and is it inside a package targeted by component scanning?
[ ] If a new Command/Query/Result object was created, is it a data class located in the Application layer (command/ or query/)?
[ ] Is there no leftover TODO, println, or temporary comment in the worked-on code?
[ ] Is the ubiquitous language consistently reflected in the code (class names, method names, property names)?
[ ] Does the comment style use only // inline comments? (KDoc usage is forbidden)
[ ] Is logger output in a structured form? (snake_case field names for external monitoring integration)
[ ] Does the commit message follow the Conventional Commits format (feat/fix/refactor + scope)?
[ ] Is the commit message's scope the service domain name (order, account, etc)?
[ ] Is the commit message's description in narrative form with no trailing period?
[ ] Is the branch name in the Conventional Branch format (<type>/<scope>-<description>), with every word in kebab-case?
[ ] Is a PR used instead of committing/pushing directly to main?
[ ] Does the PR title match the Conventional Commits format, and does the body follow the Summary + Test plan format?
[ ] Is the merge strategy Squash and merge?
[ ] Does running harness.sh pass with no FAILs? (./harness/harness.sh <projectRoot>)
```

---

## STEP 15 — Design-artifact shape (for design-stage work)

**Related documents**: [development-process.md](../../../docs/development-process.md) (root shared doc) · [reference.md](./reference.md)

> Applies only when a design-stage (RA, SD, DM, TD) artifact was written. The artifact format itself is framework-agnostic, so it follows the root document as-is.

```
[ ] RA artifact: does a functional requirement include an FR-### number, a description, Acceptance Criteria, and priority (MoSCoW)?
[ ] RA artifact: does a use case include a UC-### number, Actor, preconditions, the main flow, exception flows, and postconditions?
[ ] SD artifact: does the subdomain classification table include the type (Core/Supporting/Generic) and the implementation strategy?
[ ] SD artifact: is there a Bounded Context definition and a Context Map (relationship type + reason for the choice)?
[ ] DM artifact: does the event-storming mapping table include Actor/Command/Aggregate/Domain Event/Policy columns?
[ ] DM artifact: does the ubiquitous-language glossary include term/definition/owning Context?
[ ] DM artifact: does the per-Aggregate domain model structure include the Root/VO list/invariants (INV-###)?
[ ] TD artifact: does the package structure tree include all 4 layers — domain/application/infrastructure/interfaces? (plural interfaces — this repository's convention)
[ ] TD artifact: does the Aggregate design spec specify the protected constructor()/companion object factory/private set properties/invariants?
[ ] TD artifact: does the Repository interface definition include the find<Noun>s/save<Noun>/delete<Noun> methods (a Kotlin interface)?
[ ] TD artifact: does the Application Service definition include the @Transactional scope and failure handling (a sealed class exception)?
[ ] TD artifact: does the Event flow diagram include the Outbox → message queue → Consumer path? (not a synchronous in-process event bus)
[ ] IM artifact: is the work proceeding via Vertical Slicing (implementing per use case)?
```

---

## STEP 16 — For work editing the guide itself

**Related documents**: [development-process.md](../../../docs/development-process.md) (root shared doc) · [conventions.md](./conventions.md)

> Applies only when editing this guide itself (`docs/`, `architecture/*.md`), not when doing code work.

```
[ ] Is the newly added or edited explanation written in English?
[ ] Does a new rule come with a correct example (Kotlin code), and an incorrect example where needed?
[ ] Does the example written not violate this guide's other rules (file naming, null-safety, error handling, etc)?
[ ] If the current code in examples/ differs from the "correct pattern" the doc presents, is that difference explicitly stated as a "known gap"?
    → This is the narrative approach this repository's other architecture/*.md documents have consistently adopted
[ ] Does the rule the harness.sh checks not contradict the doc's description? (verify against the harness/harness.sh source)
[ ] Is a PR created from a new branch rather than the main branch when changing the guide?
```

---

## How to use the checklist

An AI Agent performs self-review in the following order after finishing a task:

1. Check **STEP 1 through 14 in order**.
2. When a violation is found, **fix the relevant file immediately** and check it off.
3. After fixing, confirm **there's no impact on related files** (other layers of the same domain, import references, etc).
4. If it was design-stage work, also check **STEP 15**.
5. If it was guide-editing work, also check **STEP 16**.
6. Where possible, run `./harness/harness.sh <projectRoot>` to confirm the static-check results.
7. Wrap up the task once every check is complete.

> The checklist is a summary of the guide's rules.
> If an item's intent is unclear, consult the relevant document:
> - STEP 1 File structure and naming → [conventions.md](conventions.md) sections 1-3, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 The Domain layer → [tactical-ddd.md](architecture/tactical-ddd.md), [domain-service.md](../../../docs/architecture/domain-service.md) (root shared doc), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 Layer architecture / CQRS / events → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md), [domain-events.md](architecture/domain-events.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 4 The Repository pattern → [repository-pattern.md](architecture/repository-pattern.md), [persistence.md](architecture/persistence.md)
> - STEP 5 Spring DI → [module-pattern.md](architecture/module-pattern.md), [shared-modules.md](architecture/shared-modules.md)
> - STEP 6 Kotlin typing → [conventions.md](conventions.md) section 4
> - STEP 7 Error handling → [error-handling.md](architecture/error-handling.md)
> - STEP 8 REST API endpoints → [conventions.md](conventions.md) section 5, [authentication.md](architecture/authentication.md), [rate-limiting.md](architecture/rate-limiting.md)
> - STEP 9 API documentation → [conventions.md](conventions.md) section 8
> - STEP 10 Imports → [conventions.md](conventions.md) section 7
> - STEP 11 Stereotypes / cross-cutting concerns → [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md), [scheduling.md](architecture/scheduling.md)
> - STEP 12 DB/infrastructure → [persistence.md](architecture/persistence.md), [config.md](architecture/config.md), [container.md](architecture/container.md)
> - STEP 13 Test patterns → [testing.md](architecture/testing.md)
> - STEP 14 Overall consistency → refer to the whole document set
> - STEP 15 Design-artifact shape → [development-process.md](../../../docs/development-process.md) (root shared doc)
> - STEP 16 Editing the guide → [harness/harness.sh](../harness/harness.sh)
