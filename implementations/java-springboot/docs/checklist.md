# AI Agent Self-Review Checklist (Java Spring Boot)

After completing a task, review the checklist below in order.
Check each item, and if a violation is found, fix it immediately before moving to the next item.

### Verification rules

- When verifying each STEP, always read the relevant file with the Read tool and compare it against the actual code.
- Passing an item without reading the code is forbidden.
- If a violation is found, fix it immediately, then move to the next STEP.
- `examples/` carries this repository's "known gaps" (current code intentionally left outside the scope of the documentation pass) as-is. The checklist judges against the correct pattern defined by `docs/architecture/`, not the current pattern in `examples/` — if newly written code repeats a gap from `examples/`, treat it as a violation.

---

## STEP 1 — File structure and naming

**Related documents**: [conventions.md](./conventions.md) · [architecture/directory-structure.md](./architecture/directory-structure.md)

```
[ ] Are there any file names that aren't PascalCase or don't match the public class name?
    → Rename so file name = public class name (e.g. AccountRepository.java)
[ ] Does any package name mix uppercase letters, hyphens, or underscores?
    → Use all lowercase, concatenated with no separator (e.g. com.example.accountservice.account.domain)
[ ] Is the top-level interface layer package named interface (singular)?
    → It must use interfaces (plural) to avoid colliding with the Java reserved word
[ ] Do Domain layer file names follow the naming rules?
    → Aggregate Root: <Aggregate>.java, child Entity: <Entity>.java, Value Object: <Concept>.java (record), Domain Event: <DomainEvent>.java (record, past tense)
[ ] Is the Repository interface file named <Aggregate>Repository.java and located in domain/?
[ ] Is the Repository implementation file named <Aggregate>RepositoryImpl.java and located in infrastructure/persistence/?
[ ] Is the Spring Data JPA interface file named <Aggregate>JpaRepository.java and located in infrastructure/persistence/?
[ ] Is the Command Service file named <Verb><Noun>Service.java and located in application/command/?
[ ] Is the Query Service file named Get<Noun>Service.java and located in application/query/?
[ ] Are the Command/Result record files named <Verb><Noun>Command.java / <Verb><Noun>Result.java?
[ ] Is the Domain Event Handler file named with a role-revealing name such as <Domain>NotificationListener and located in application/event/?
[ ] Is the Technical Service interface file named <Concern>Service.java and located in application/service/?
[ ] Is the Technical Service implementation file named <Concern>ServiceImpl.java and located in infrastructure/?
[ ] Is the Adapter interface file named <ExternalDomain>Adapter.java and located in application/adapter/?
[ ] Is the Adapter implementation file named <ExternalDomain>AdapterImpl.java and located in infrastructure/?
[ ] Is the HTTP Controller file named <Domain>Controller.java and located in interfaces/rest/?
[ ] Is the Interface DTO file named <Verb><Noun>Request.java / does the response return the Application Result as-is (no separate Response class needed)?
[ ] Do class names follow the naming rules?
    → Aggregate Root: a domain noun (Account) · Value Object: a concept name (Money) · Domain Event: past tense (MoneyDepositedEvent)
    → Repository interface: <Aggregate>Repository / implementation: <Aggregate>RepositoryImpl
    → Command: <Verb><Noun>Command / Result: <Verb><Noun>Result
    → Exception: <Domain>Exception, with an internal ErrorCode enum in SCREAMING_SNAKE_CASE
[ ] Are constants UPPER_SNAKE_CASE? Are methods/fields camelCase? Are Enum constants UPPER_SNAKE_CASE?
```

---

## STEP 2 — The Domain layer

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/tactical-ddd.md](./architecture/tactical-ddd.md) · [architecture/aggregate-id.md](./architecture/aggregate-id.md)

```
[ ] Does the domain/ package contain the Aggregate Root, child Entities, Value Objects, Domain Events, and the Repository interface?
[ ] Does the Aggregate Root encapsulate business rules and invariants?
    → If an Application Service contains state-change condition checks or calculation logic, move it to an Aggregate domain method
[ ] Is the Aggregate Root created only via a static factory method (static create())?
    → No public constructor is left open; block it with protected <Aggregate>() {} (leaving only the default constructor JPA requires)
[ ] Is a child Entity's creation method package-private static, not public?
    → Direct creation bypassing the Aggregate Root must be blocked at compile time
[ ] Is a Value Object defined as a record + compact canonical constructor so validation always runs?
    → No separate constructor that directly assigns fields is left open
[ ] Do a Value Object's operation methods (add, subtract, etc.) return a new instance? (never modify a field directly)
[ ] Is a Domain Event an immutable record with a past-tense name?
[ ] Does a Domain layer file have a Spring annotation (@Service, @Component, @Repository, @Controller, @RestController)?
    → Remove it if present. Domain must be framework-independent by principle
[ ] Does a Domain layer file carry JPA annotations like @Entity/@Embeddable/@Column?
    → This repository already explicitly documents that a JPA entity doubling as the domain object is a "known gap" (see layer-architecture.md) — a new Aggregate following the same pattern (dual-purposed as JPA) is not by itself an immediate violation. However, this fact must not be hidden in docs/PRs, and once an Aggregate grows complex (polymorphism, several child-entity collections), separating domain/AccountEntity should be considered
[ ] Is the Repository interface a plain Java interface located in domain/? (not an abstract class)
[ ] Are Aggregates referenced only by ID (String), with no direct cross-references?
    → Never hold another Aggregate/BC directly as a field type (just as only ownerId is held, never a User object)
[ ] Is there code that directly changes internal state from outside the Aggregate, via a setter or similar?
    → If so, change it to go through a domain method on the Aggregate Root
[ ] Is the ID generated via IdGenerator (32-character hex, hyphens removed) on Aggregate creation?
    → Never assign UUID.randomUUID().toString() directly to accountId as-is (including hyphens is a violation — see aggregate-id.md)
[ ] When restoring from the DB, is the stored value reused as-is rather than issuing a new ID?
[ ] Are the JPA @Id surrogate key (Long, auto-increment) and the domain identifier (String, 32-character hex) kept separate?
    → Never expose the auto-increment value in an API response/event/external reference
[ ] Is a Domain Event collected in a @Transient field, then drained and cleared via pullDomainEvents()?
```

---

## STEP 3 — Layer architecture / Application Services

**Related documents**: [architecture/layer-architecture.md](./architecture/layer-architecture.md) · [architecture/cqrs-pattern.md](./architecture/cqrs-pattern.md)

```
[ ] Does a Controller perform any logic beyond Command/Query conversion + calling a Service?
    → If so, move it to an Application Service
[ ] Does an Application Service (Command/Query) perform business logic directly? (state-change condition checks, calculations, etc.)
    → If so, move it to a domain method on the Aggregate
[ ] Does an Application Service reference an HTTP-related type (HttpStatus, ResponseEntity, etc.)?
    → Remove it if present. HTTP conversion happens only in the Interfaces layer (@ExceptionHandler)
[ ] Are write use cases in application/command/ and read use cases in application/query/?
[ ] Does a Command Service carry @Service + @RequiredArgsConstructor? (there must be no @Transactional — the transaction boundary has moved to Repository.save(), see STEP 8)
[ ] Does a Query Service carry @Service + @RequiredArgsConstructor + @Transactional(readOnly = true)?
[ ] Does a Query Service directly use the write-side Repository (<Aggregate>Repository)?
    → This is a known gap documented by cqrs-pattern.md. A newly written domain should not repeat this gap — introduce a dedicated <Aggregate>Query interface (application/query/) so the Query Service uses only this interface
[ ] Is the <Aggregate>Query interface implementation located in infrastructure/persistence/, querying via a projection query matching the response schema without loading the full Aggregate?
[ ] Is there a class using field injection (@Autowired private X x)?
    → If so, replace it with constructor injection (Lombok @RequiredArgsConstructor)
[ ] Is the layer dependency direction correct? (interfaces → application → domain ← infrastructure)
    → If a lower layer imports an upper layer, fix it
[ ] Does an Interface DTO (record) contain logic beyond a one-line conversion that simply carries over Application Command/Result fields?
    → If so, move that logic to the Application layer
[ ] Does the Repository's save() method save the Aggregate together with child entities (pendingTransactions, etc.)?
    → An Application Service must not save a child entity separately
```

---

## STEP 4 — The Repository pattern

**Related documents**: [architecture/repository-pattern.md](./architecture/repository-pattern.md)

```
[ ] Is the Repository defined at the Aggregate Root level? (never create a separate Repository per table/child entity)
[ ] Is the Repository interface a plain interface located in domain/?
[ ] Is the Repository implementation located in infrastructure/persistence/, carrying @Repository + @RequiredArgsConstructor?
[ ] Does the Repository lookup method name follow the find<Noun> (single record)/find<Noun>s or findAll (list) pattern?
[ ] Is the Repository save method named save<Noun>() or save()?
[ ] Does the Repository have a delete<Noun>() method, implemented as a soft delete (setting deletedAt)?
    → This is a known gap documented by repository-pattern.md/persistence.md. Don't confuse an account being "closed" (a status transition) with being "deleted" (setting deletedAt) — a new Aggregate should implement both
[ ] Does the Repository have an update<Noun>() method?
    → If so, remove it. Look up first, change state via an Aggregate domain method, then save via save()
[ ] Does the Repository implementation appropriately distinguish between Spring Data <Aggregate>JpaRepository (derived queries) and EntityManager (dynamic JPQL)?
    → Fixed conditions use a JpaRepository derived query; dynamic filters are assembled via EntityManager
[ ] When assembling a dynamic filter, is a condition added only when a value is present? (add AND only after a null/blank/empty check)
[ ] Does the Repository implementation treat the result as a domain Aggregate rather than returning the raw DB record as-is?
    → Since this repository has a JPA entity double as the domain object, having Account itself returned with no separate mapping code is not a violation (a known gap, see STEP 2) — however, the Query path must return a projection result (a Result record), not the full Aggregate
[ ] Does a Repository lookup method apply a deletedAt IS NULL condition by default?
[ ] Does the Aggregate collect child entities in a @Transient collection like pendingTransactions, with Repository.save() saving them together?
```

---

## STEP 5 — The Spring DI container / package boundaries

**Related documents**: [architecture/module-pattern.md](./architecture/module-pattern.md) · [architecture/shared-modules.md](./architecture/shared-modules.md) · [architecture/cross-domain.md](./architecture/cross-domain.md)

```
[ ] Is constructor injection used exclusively? (Lombok @RequiredArgsConstructor + final fields)
    → Remove any @Autowired field injection
[ ] Do the stereotype annotations match the actual layer?
    → @Service only in application/, @Repository only in infrastructure/, @RestController only in interfaces/rest/
[ ] Was a third-party type (an AWS SDK client, etc.) registered directly with @Component?
    → Register it via a @Bean factory method on a @Configuration class instead
[ ] Are top-level packages split by domain (Bounded Context), not by layer (controllers/, services/)?
[ ] Does a single domain package contain all 4 subpackages — domain/application/infrastructure/interfaces?
[ ] Does an Application Service directly inject another domain's (BC's) Service/Repository?
    → If so, switch to the Adapter pattern: an interface in application/adapter/, an implementation in infrastructure/
[ ] Is the Adapter interface defined in the caller's (its own BC's) application/adapter/? (not the callee's)
[ ] Does the Adapter call another BC's write method?
    → Forbidden. An Adapter only reads (ACL). If a state change is needed, switch to an Integration Event
[ ] Is a circular dependency worked around with @Lazy?
    → If so, remove it. Redesign the Bounded Context boundary, or switch to an Adapter/event-based communication. @Lazy is not a genuine fix for a circular dependency
[ ] Is @Profile used for conditional bean registration per environment? (instead of a hardcoded if (env.equals("prod")) branch)
```

---

## STEP 6 — Error handling

**Related documents**: [architecture/error-handling.md](./architecture/error-handling.md)

```
[ ] Does a Domain method throw a typed exception (<Domain>Exception) immediately on an invariant violation?
    → Never throw a free-form string or a checked exception
[ ] Does <Domain>Exception carry both an ErrorCode enum (SCREAMING_SNAKE_CASE) and a message?
[ ] Does the Application layer propagate an exception as-is, without catching it?
    → A Command/Query Service must not swallow it with try-catch
[ ] Does HTTP exception conversion happen only in the Interfaces layer (@ExceptionHandler or @RestControllerAdvice)?
    → Does Domain/Application reference HttpStatus/ResponseEntity? Remove it if present
[ ] Does the error response body follow the 4-field format { statusCode, code, message, error }?
    → This repository's ErrorResponse already has the 4 fields (statusCode, code, message, error). Newly written code should also follow this format via ErrorResponse.of(status, code, message)
[ ] When there are 2 or more domains, is a domain-agnostic exception like MethodArgumentNotValidException handled in a @RestControllerAdvice global class?
    → Don't redefine it redundantly in each Controller
[ ] Is the code of a validation-failure response the fixed value VALIDATION_FAILED?
[ ] Does @ExceptionHandler log (log.warn/error) before converting the exception into a response?
```

---

## STEP 7 — REST API endpoints

**Related documents**: [conventions.md](./conventions.md) · [architecture/api-response.md](./architecture/api-response.md) · [architecture/authentication.md](./architecture/authentication.md)

```
[ ] Is the URL built from plural noun resources rather than verbs?
    → Correct: GET /accounts, POST /accounts · Incorrect: GET /getAccounts, POST /createAccount
[ ] Is the resource name plural?
[ ] Does the URL use only lowercase kebab-case?
[ ] Is the HTTP method used correctly? (GET for reads, POST for creation, PUT for full updates, PATCH for partial updates, DELETE for deletion)
[ ] Is a non-CRUD action expressed as a sub-resource path? (e.g. POST /accounts/:accountId/suspend)
[ ] Does the response code match the HTTP method/@ResponseStatus? (201 for POST, 204 for DELETE/a status-transition-style POST, 200 for GET)
[ ] Are the pagination parameters page (0-based)/take, received via @RequestParam(defaultValue = ...)?
[ ] Is the key name of a list-query response the plural of the domain object name? (e.g. transactions, count)
    → A generic key like { data: [...] }, { result: [...] } is forbidden
[ ] Is a single-record response returned as the Result record as-is, not wrapped in a generic envelope like { success, data }?
[ ] Is an unauthenticated value (@RequestHeader("X-User-Id"), etc.) being trusted?
    → This repository has a known gap of trusting the X-User-Id header as-is (see authentication.md). If a domain has adopted Spring Security, it must extract userId from @AuthenticationPrincipal/Authentication instead
[ ] Does the URL have no trailing slash or file extension?
```

---

## STEP 8 — Transactions / Domain Events

**Related documents**: [architecture/persistence.md](./architecture/persistence.md) · [architecture/domain-events.md](./architecture/domain-events.md) · [architecture/scheduling.md](./architecture/scheduling.md)

```
[ ] Does a Command Service method have no @Transactional? (the transaction boundary must be on Repository.save() — having it on the Command Service is a regression)
[ ] Does a Query Service method have @Transactional(readOnly = true)?
[ ] Does Repository.save() have @Transactional, with writes spanning multiple Repositories happening inside a single @Transactional method?
[ ] Is Propagation.REQUIRES_NEW used for side work (sending a notification, etc.) that must be isolated from the original transaction?
    → A failure in the side work must not contaminate the original transaction into rollback-only
[ ] Is a Domain Event created only inside an Aggregate's internal domain method?
    → A Command Service must never create an event directly
[ ] Does a Command Service avoid calling ApplicationEventPublisher.publishEvent() directly?
    → If it does, that's a regression. The event is saved to the Outbox table inside Repository.save(), and the Command Service returns immediately after saving — the drain is handled entirely by the separate OutboxPoller/OutboxConsumer processes (see domain-events.md)
[ ] Does Repository.save() atomically handle both the Aggregate save and the Outbox save inside a single @Transactional?
[ ] Does a Command Service (application/command/) avoid referencing the OutboxPoller/OutboxConsumer symbols or calling processPending()/poll()/drainOnce()? (the harness's outbox-drain-order rule catches this regression — see domain-events.md)
[ ] Is OutboxPoller located in the outbox/ package, polling DB→SQS via @Scheduled(fixedDelay=1000)? Does OutboxConsumer handle SQS→Handler asynchronously via SmartLifecycle? (see domain-events.md)
[ ] Is @EnableScheduling declared on the @SpringBootApplication class?
    → Without it, @Scheduled is silently disabled
[ ] Does a @Scheduled method explicitly log any exception? (so it isn't silently swallowed)
[ ] Is an event handler (EventListener/Consumer) implemented idempotently? (to handle at-least-once delivery)
[ ] Does it prevent duplicate scheduler execution across multiple instances, via FIFO dedup or ShedLock?
```

---

## STEP 9 — Authentication / cross-cutting concerns

**Related documents**: [architecture/authentication.md](./architecture/authentication.md) · [architecture/cross-cutting-concerns.md](./architecture/cross-cutting-concerns.md) · [architecture/rate-limiting.md](./architecture/rate-limiting.md)

```
[ ] Is authentication handled only in the Interfaces layer (SecurityFilterChain/Controller)?
    → Does an Application Service parse a token/Authorization header directly? Remove it if present
[ ] Is authorizeHttpRequests applied filter-chain-wide, managing only a whitelist (permitAll()) as the exception?
[ ] Does the JWT payload carry only userId (subject), with no mutable/sensitive information like role/email?
[ ] Is the Correlation ID injected in a Filter (OncePerRequestFilter, @Order(HIGHEST_PRECEDENCE)) and stored in MDC?
[ ] Is the MDC value always cleaned up with try-finally? (to prevent value leakage when the thread pool is reused)
[ ] Is request logging implemented via HandlerInterceptor? (don't confuse its role with Filter's)
[ ] Does the Domain layer avoid using cross-cutting concerns like Logger/MDC/Spring Security?
[ ] If Rate Limiting has been introduced, is it applied via a Filter that runs before authentication?
    → This repository applies double defense with `RateLimitFilter` (global, excluding `/actuator/**`) and the `@RateLimiter` annotation (per-endpoint) (see rate-limiting.md)
[ ] Does a Rate Limiting exceeded-response follow the 429 + 4-field error format? (if adopted)
[ ] Are the health-check/Actuator endpoints excluded from authentication/rate limiting?
```

---

## STEP 10 — Configuration / infrastructure

**Related documents**: [architecture/config.md](./architecture/config.md) · [architecture/secret-manager.md](./architecture/secret-manager.md) · [architecture/container.md](./architecture/container.md) · [architecture/graceful-shutdown.md](./architecture/graceful-shutdown.md) · [architecture/local-dev.md](./architecture/local-dev.md) · [architecture/observability.md](./architecture/observability.md)

```
[ ] Are configuration values scattered across multiple classes via @Value?
    → Group them into a @ConfigurationProperties record per concern and validate at startup with @Validated
[ ] Is @ConfigurationProperties binding confined to the Infrastructure layer (@Configuration/@Component)?
    → Does an Application Service/Domain reference a configuration value directly? Remove it if present
[ ] In the production profile (application-prod.yml), is a sensitive-value placeholder declared with no default (${VAR}), forcing fail-fast?
[ ] Is a sensitive value (DB password, JWT secret, API key) never hardcoded in code/config files?
    → A production environment looks it up via SecretService (AWS Secrets Manager) + a TTL cache
[ ] Is ddl-auto: update auto-synchronizing the production schema?
    → This is a known gap. Adopt a migration tool (Flyway) and switch to ddl-auto: validate. create-drop in the test environment (Testcontainers) is allowed as an exception
[ ] Does the Dockerfile use a multi-stage Layered JAR build? (Build → Extract → Runtime, 3 stages)
[ ] Is the runtime image JRE-based? (not JDK)
[ ] Is ENTRYPOINT in exec form (["java", "..."])? (shell form/a gradlew wrapper is forbidden)
[ ] Does the container run as a non-root user?
[ ] Are server.shutdown: graceful + spring.lifecycle.timeout-per-shutdown-phase configured?
[ ] Are Actuator's liveness/readiness probes enabled?
[ ] Are logs in structured JSON form with snake_case field names? (using StructuredArguments.kv)
[ ] Does the Domain layer avoid performing any logging?
```

---

## STEP 11 — Testing patterns

**Related documents**: [architecture/testing.md](./architecture/testing.md)

```
[ ] Are Domain unit tests written in pure Java with no Spring context? (calling new Account.create(...) directly)
[ ] Do Application unit tests mock the Repository/Query interface via @ExtendWith(MockitoExtension.class) + @Mock?
    → Never mock a concrete class (<Aggregate>RepositoryImpl)
[ ] Do E2E tests verify a real HTTP request via @Testcontainers + @SpringBootTest(webEnvironment = RANDOM_PORT)?
[ ] Do E2E tests use a real Testcontainers container (Postgres, etc.), not an in-memory DB?
[ ] Are there tests for Aggregate invariant violations (invalid input → verifying <Domain>Exception + ErrorCode)?
[ ] Is there a test verifying Domain Event publication via pullDomainEvents()?
[ ] Are test files placed in the standard Gradle source set (src/test/java, mirroring the same package)?
[ ] Do test method names follow this repository's convention (a full Korean sentence, e.g. 정지된_계좌에_입금하면_예외를_던진다())?
    → The English _when_..._then_ pattern is not enforced. A single Korean sentence revealing intent is sufficient
[ ] Is exception verification done against the ErrorCode enum value, not a string message? (assertThatThrownBy + extracting)
```

---

## STEP 12 — Final consistency check

**Related documents**: [conventions.md](./conventions.md) · [architecture/design-principles.md](./architecture/design-principles.md)

```
[ ] Is a newly added class located in the correct layer package (domain/application/infrastructure/interfaces)?
[ ] Does the code you worked on leave behind any TODO, System.out.println, or temporary comments?
[ ] Is the Ubiquitous Language consistently reflected in the code (class names, method names, variable names)?
[ ] Does the comment style use only // inline comments? (avoid verbose JSDoc-style block comments; use Javadoc only to describe a public API)
[ ] Is logger output in structured form? (snake_case field names, StructuredArguments.kv)
[ ] Does the commit message follow the Conventional Commits format (feat/fix/refactor + scope)?
[ ] Is the commit message's scope the service domain name (account, notification, etc.)?
[ ] Is the commit message's description written in the descriptive form with no trailing period?
[ ] Does the branch name follow the Conventional Branch format (<type>/<scope>-<description>), with every word in kebab-case?
[ ] Is work landed via a PR rather than committing/pushing directly to the main branch?
[ ] Does the PR body follow the Summary + Test plan format?
[ ] Is the merge strategy Squash and merge?
[ ] Does running ./harness.sh <projectRoot> show no FAIL items?
    → If a FAIL is found, open the architecture/*.md document the harness message points to and fix it
```

---

## STEP 13 — Design deliverable format (for design-stage work)

**Related documents**: [development-process.md](../../../docs/development-process.md) (root shared doc) · [reference.md](./reference.md)

> This applies only when producing design-stage (RA, SD, DM, TD) deliverables. The framework-agnostic criteria are identical to STEP 11 of the root [checklist.md](../../../docs/checklist.md) — here, only the points commonly missed in Java Spring Boot deliverables are reinforced.

```
[ ] RA deliverable: do functional requirements include an FR-### number, a description, acceptance criteria, and a MoSCoW priority?
[ ] RA deliverable: does each use case include a UC-### number, Actor, preconditions, main flow, exception flow, and postconditions?
[ ] SD deliverable: does the subdomain classification table include the type (Core/Supporting/Generic) and the implementation strategy?
[ ] SD deliverable: does the Bounded Context definition document include responsibilities, key concepts, and the owning subdomain?
[ ] DM deliverable: does the event storming result mapping table include Actor/Command/Aggregate/Domain Event/Policy columns?
[ ] DM deliverable: does the Ubiquitous Language glossary include term (English)/term (Korean)/definition/owning Context?
[ ] DM deliverable: does the per-Aggregate domain model structure include the Root/child Entity list/Value Object list/relationships?
[ ] DM deliverable: do business rules/invariants include an INV-### number and how a violation is handled (which ErrorCode)?
[ ] TD deliverable: does the file structure tree include all 4 layers — domain/application/infrastructure/interfaces? (confirm interfaces is plural)
[ ] TD deliverable: does the Repository interface definition document specify the find/save/delete methods and return types?
[ ] TD deliverable: does the Aggregate design document include the static factory method/invariants/event list/ID generation rule (32-character hex)?
[ ] TD deliverable: does the DI configuration specify the stereotype annotations (@Service/@Repository/@Component) and the constructor-injection approach?
[ ] IM deliverable: is work proceeding via Vertical Slicing (implementation by use case)?
```

---

## STEP 14 — Guide-editing work

**Related documents**: [development-process.md](../../../docs/development-process.md) (root shared doc) · [conventions.md](./conventions.md)

> This applies only when editing this guide (`docs/`) itself, rather than doing code work.

```
[ ] Is the newly added or modified prose written in English?
[ ] Is a new rule accompanied by an actual code example (correct approach/incorrect approach)?
[ ] Does the example avoid contradicting the pattern actually documented by architecture/*.md?
    → If there's a contradiction, check the architecture/ document first and align the example with it
[ ] Have you avoided using a known gap in examples/ as an example, as if it were the correct pattern?
[ ] When changing the guide, is a PR created from a new branch rather than the main branch?
```

---

## How to use this checklist

After completing a task, the AI Agent performs self-review in the following order:

1. Check **STEP 1 through 12 in order**.
2. If a violation is found, **fix the relevant file immediately** and check it off.
3. After fixing, confirm **there's no impact on related files** (other classes in the same domain package, DI targets, etc.).
4. If the work was design-stage work, also check **STEP 13**.
5. If the work was guide-editing work, also check **STEP 14**.
6. After all checks are complete, do a final confirmation of structural rules with `./harness.sh <projectRoot>`.

> The checklist is a summary of the guide's rules.
> If an item's intent is unclear, refer to the corresponding document:
> - STEP 1 File structure and naming → [conventions.md](conventions.md) sections 1–2, [directory-structure.md](architecture/directory-structure.md)
> - STEP 2 Domain layer → [tactical-ddd.md](architecture/tactical-ddd.md), [aggregate-id.md](architecture/aggregate-id.md)
> - STEP 3 Layer architecture → [layer-architecture.md](architecture/layer-architecture.md), [cqrs-pattern.md](architecture/cqrs-pattern.md)
> - STEP 4 Repository pattern → [repository-pattern.md](architecture/repository-pattern.md)
> - STEP 5 Spring DI → [module-pattern.md](architecture/module-pattern.md), [cross-domain.md](architecture/cross-domain.md)
> - STEP 6 Error handling → [error-handling.md](architecture/error-handling.md)
> - STEP 7 REST API → [conventions.md](conventions.md) section 5, [api-response.md](architecture/api-response.md)
> - STEP 8 Transactions/events → [persistence.md](architecture/persistence.md), [domain-events.md](architecture/domain-events.md)
> - STEP 9 Authentication/cross-cutting concerns → [authentication.md](architecture/authentication.md), [cross-cutting-concerns.md](architecture/cross-cutting-concerns.md)
> - STEP 10 Configuration/infrastructure → [config.md](architecture/config.md), [container.md](architecture/container.md)
> - STEP 11 Testing patterns → [testing.md](architecture/testing.md)
> - STEP 12 Overall consistency → [conventions.md](conventions.md), harness/harness.sh
> - STEP 13 Design deliverable format → [development-process.md](../../../docs/development-process.md) (root shared doc)
> - STEP 14 Guide editing → this repository's CLAUDE.md guide-management principles
